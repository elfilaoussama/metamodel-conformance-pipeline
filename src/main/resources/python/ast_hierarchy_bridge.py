import ast
import builtins
import hashlib
import json
import os
import pathlib
import sys
import tokenize
from dataclasses import dataclass, replace


def runtime_executable_snapshot():
    # Linux proc identifies the running image, including when a launcher execs it.
    # Other platforms use Python's documented executable path; unavailable paths
    # reject rather than silently falling back to a version-only identity.
    executable = pathlib.Path("/proc/self/exe") if sys.platform.startswith("linux") else pathlib.Path(sys.executable or "")
    executable = executable.resolve(strict=True)
    if not executable.is_file():
        raise RuntimeError("Python runtime executable is unavailable")
    digest = hashlib.sha256()
    with executable.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"path": str(executable), "sha256": digest.hexdigest()}


runtime_executable_before = runtime_executable_snapshot()


def runtime_configuration_snapshot():
    if not all(isinstance(entry, str) for entry in sys.path):
        raise RuntimeError("unsupported non-string Python module search path")
    return {"searchPath": list(sys.path), "prefix": sys.prefix,
            "basePrefix": sys.base_prefix, "flags": str(sys.flags),
            "workingDirectory": os.getcwd()}


def runtime_module_snapshot():
    result = {}
    captured_files = {}
    for name, module in sorted(sys.modules.copy().items()):
        spec = getattr(module, "__spec__", None)
        origin = getattr(spec, "origin", None)
        origin = origin if isinstance(origin, str) else "<unknown>"
        files = {}
        # Both are reported backing locations; neither alone proves execution.
        for value in (getattr(module, "__file__", None), getattr(spec, "cached", None)):
            if not isinstance(value, str) or not value:
                continue
            location = pathlib.Path(value).absolute()
            if not location.is_file():
                continue
            # Retain logical and physical paths, including alias-retarget coverage.
            for path in dict.fromkeys((location, location.resolve(strict=True))):
                key = str(path)
                if key not in captured_files:
                    digest = hashlib.sha256()
                    with path.open("rb") as stream:
                        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                            digest.update(chunk)
                    captured_files[key] = digest.hexdigest()
                files[key] = captured_files[key]
        status = ("FILES_REPORTED" if files else "BUILTIN_OR_FROZEN"
                  if origin in ("built-in", "frozen") else "BRIDGE_MAIN"
                  if name == "__main__" else "UNAVAILABLE")
        result[name] = {"name": name, "origin": origin, "backingStatus": status,
                        "files": [{"path": path, "sha256": digest}
                                  for path, digest in sorted(files.items())]}
    return result


runtime_configuration_before = runtime_configuration_snapshot()
runtime_modules_before = runtime_module_snapshot()
request = json.load(sys.stdin)
root = pathlib.Path(request["root"])
paths = sorted(request["paths"])
classes = []
diagnostics = []
hierarchy_incomplete = False


def module_name(rel):
    parts = list(pathlib.PurePosixPath(rel).parts)
    stem = parts[-1][:-3]
    if stem == "__init__":
        parts = parts[:-1]
    else:
        parts[-1] = stem
    return ".".join(parts) if parts else "<root>"


def child_name(prefix, name):
    if not prefix or prefix == "<root>":
        return name
    return prefix + "." + name


def definition_key(rel, node, qualified):
    return rel + "\0" + str(getattr(node, "lineno", 1) or 1) + "\0" + qualified


def member_definition_key(rel, node, owner, kind, name):
    return "\0".join(
        [
            rel,
            str(getattr(node, "lineno", 1) or 1),
            str(getattr(node, "col_offset", 0) or 0),
            owner,
            kind,
            name,
        ]
    )


def absolute_from(module, is_package, level, imported):
    if not level:
        return imported or ""
    if module == "<root>":
        package = ""
    else:
        package = module if is_package else module.rpartition(".")[0]
    parts = [p for p in package.split(".") if p]
    up = level - 1
    if up > len(parts):
        return None
    if up:
        parts = parts[:-up]
    if imported:
        parts.extend(imported.split("."))
    return ".".join(parts)


def dotted(expr):
    while isinstance(expr, ast.Subscript):
        expr = expr.value
    if isinstance(expr, ast.Name):
        return expr.id
    if isinstance(expr, ast.Attribute):
        left = dotted(expr.value)
        return None if left is None else left + "." + expr.attr
    return None


def dynamic_binding():
    return ("dynamic", None, None)


def qualified_binding(target):
    return ("qualified", None, target)


def definition_binding(key, qualified):
    return ("definition", key, qualified)


def builtin_binding(target):
    return ("builtin", None, target)


def namespace_key(target):
    return "\0namespace-unknown:" + target.split(".")[0]


def invalidate_namespace(name, bindings):
    bound = bindings.get(name)
    if bound is None or bound[0] not in ("qualified", "definition", "builtin"):
        return
    bindings[namespace_key(bound[2])] = dynamic_binding()
    # An old captured callable survives a module attribute rebind, while aliases
    # of the mutated namespace/object itself all lose their member-lookup proof.
    for alias, value in list(bindings.items()):
        if value == bound:
            bindings[alias] = dynamic_binding()


def namespace_writes(body, include_class_bodies=False):
    roots = set()

    class Visitor(ast.NodeVisitor):
        def visit_Attribute(self, node):
            name = dotted(node.value)
            if isinstance(node.ctx, (ast.Store, ast.Del)) and name:
                roots.add(name.split(".")[0])
            self.generic_visit(node)

        def visit_FunctionDef(self, node):
            return

        visit_AsyncFunctionDef = visit_FunctionDef

        def visit_ClassDef(self, node):
            if include_class_bodies:
                self.generic_visit(node)

    visitor = Visitor()
    for statement in body:
        visitor.visit(statement)
    return roots


def resolve_reference(expr, bindings, star_imported=False):
    raw = dotted(expr)
    if raw is None:
        return dynamic_binding()

    parts = raw.split(".")
    bound = bindings.get(parts[0])
    if bound is not None:
        kind, key, target = bound
        if kind == "dynamic" or target is None:
            return dynamic_binding()
        if len(parts) == 1:
            return bound
        if namespace_key(target) in bindings:
            return dynamic_binding()
        suffix = "." + ".".join(parts[1:])
        return qualified_binding(target + suffix)

    # A wildcard import may supply or replace any otherwise unbound name,
    # including names that normally fall through to builtins.
    if star_imported:
        return dynamic_binding()

    if len(parts) == 1:
        if namespace_key("builtins") in bindings:
            return dynamic_binding()
        value = getattr(builtins, raw, None)
        if isinstance(value, type):
            return builtin_binding("builtins." + raw)

    return qualified_binding(raw)


def resolve_base(expr, bindings, star_imported):
    raw = dotted(expr)
    if raw is None:
        try:
            display = ast.unparse(expr)
        except Exception:
            display = "<dynamic-python-base>"
        return {
            "raw": display,
            "definitionCandidate": None,
            "qualifiedCandidate": None,
            "builtinCandidate": None,
            "line": getattr(expr, "lineno", 1),
        }

    kind, key, target = resolve_reference(expr, bindings, star_imported)
    if kind == "definition":
        return {
            "raw": raw,
            "definitionCandidate": key,
            "qualifiedCandidate": target,
            "builtinCandidate": None,
            "line": getattr(expr, "lineno", 1),
        }
    if kind == "qualified":
        return {
            "raw": raw,
            "definitionCandidate": None,
            "qualifiedCandidate": target,
            "builtinCandidate": None,
            "line": getattr(expr, "lineno", 1),
        }
    if kind == "builtin":
        return {
            "raw": raw,
            "definitionCandidate": None,
            "qualifiedCandidate": target,
            "builtinCandidate": target,
            "line": getattr(expr, "lineno", 1),
        }
    return {
        "raw": raw,
        "definitionCandidate": None,
        "qualifiedCandidate": None,
        "builtinCandidate": None,
        "line": getattr(expr, "lineno", 1),
    }


def assigned_names(target):
    if isinstance(target, ast.Name):
        return [target.id]
    if isinstance(target, (ast.Tuple, ast.List)):
        result = []
        for element in target.elts:
            result.extend(assigned_names(element))
        return result
    return []


def mangled_name(name, owner):
    # CPython private name mangling: a class-body identifier starting with two
    # underscores and not ending with two underscores is rewritten to
    # _<ClassName><name> before class creation, so the effective attribute name
    # observed through inheritance is the mangled form.
    if name.startswith("__") and not name.endswith("__"):
        # CPython removes leading owner underscores; an all-underscore owner
        # has no nonempty mangling prefix and leaves the identifier unchanged.
        simple = owner.rsplit(".", 1)[-1].lstrip("_")
        if simple:
            return "_" + simple + name
    return name


def method_descriptor(node, bindings, star_imported):
    """Resolve supported decorator identities; spelling is not semantic evidence."""
    descriptor = "INSTANCE"
    abstraction = "CONCRETE"
    for decorator in reversed(node.decorator_list):
        if isinstance(decorator, ast.Call):
            return "UNKNOWN", "UNKNOWN"
        kind, _, target = resolve_reference(decorator, bindings, star_imported)
        if kind not in ("qualified", "builtin"):
            return "UNKNOWN", "UNKNOWN"
        if target.startswith("abc.") and any(name == "abc" or name.startswith("abc.") for name in source_modules):
            return "UNKNOWN", "UNKNOWN"
        if target == "abc.abstractmethod" and descriptor == "INSTANCE":
            abstraction = "ABSTRACT"
        elif target in ("builtins.staticmethod", "builtins.classmethod") and descriptor == "INSTANCE":
            descriptor = "STATIC" if target == "builtins.staticmethod" else "CLASS"
        elif target in ("abc.abstractstaticmethod", "abc.abstractclassmethod") and descriptor == "INSTANCE":
            descriptor = "STATIC" if target == "abc.abstractstaticmethod" else "CLASS"
            abstraction = "ABSTRACT"
        else:
            # Unknown transformations, properties and unsupported stacking are
            # not evidence of either concrete/instance or abstract/static status.
            return "UNKNOWN", "UNKNOWN"
    return descriptor, abstraction


def signature_subset(node, descriptor):
    """Annotation-based signature subset. Returns (type_tokens, supported).

    The supported subset excludes the implicit receiver of a known instance or
    class descriptor, regardless of its parameter name, then requires fully-annotated
    positional parameters. Positional-only markers, keyword-only parameters,
    variadic parameters, and defaults are unsupported shapes: the observation
    schema cannot represent them yet, so such methods stay outside the subset
    and local-signature evidence remains incomplete for the observation.
    Parameter names are never used as type tokens.
    """
    args = node.args
    if descriptor == "UNKNOWN":
        return [], False
    if args.posonlyargs or args.kwonlyargs or args.vararg or args.kwarg:
        return [], False
    if args.defaults:
        return [], False
    positional = list(args.args)
    if positional and descriptor in ("INSTANCE", "CLASS"):
        positional = positional[1:]
    tokens = []
    for argument in positional:
        if argument.annotation is None:
            return [], False
        try:
            tokens.append(ast.unparse(argument.annotation))
        except Exception:
            return [], False
    return tokens, True


def class_member(node, owner, kind, name, descriptor=None, abstraction=None):
    name = mangled_name(name, owner)
    member = {
        "definitionKey": member_definition_key(current_rel, node, owner, kind, name),
        "kind": kind, "name": name,
        "line": getattr(node, "lineno", 1) or 1,
        "endLine": getattr(node, "end_lineno", None) or getattr(node, "lineno", 1) or 1,
    }
    if kind == "METHOD":
        parameters, supported = signature_subset(node, descriptor)
        member.update(parameters=parameters, signatureSupported=supported,
                      returnAnnotation=ast.unparse(node.returns) if node.returns is not None else None,
                      abstraction=abstraction,
                      scope=descriptor if descriptor in ("INSTANCE", "STATIC") else "UNKNOWN")
    return member


def scope_declarations(body):
    globals_ = set()
    nonlocals = set()

    class Visitor(ast.NodeVisitor):
        def visit_Global(self, node):
            globals_.update(node.names)

        def visit_Nonlocal(self, node):
            nonlocals.update(node.names)

        def visit_FunctionDef(self, node):
            return

        visit_AsyncFunctionDef = visit_FunctionDef
        visit_ClassDef = visit_FunctionDef

    visitor = Visitor()
    for statement in body:
        visitor.visit(statement)
    return globals_, nonlocals


@dataclass(frozen=True)
class ScopeContext:
    """Lexical frames persist through statement branches, which are not scopes."""
    global_bindings: dict | None = None
    global_star: bool = False
    method_bindings: dict | None = None
    method_star: bool = False
    deferred_names: frozenset = frozenset()
    deferred_star: bool = False
    members: list | None = None
    outer_names: frozenset = frozenset()
    global_names: frozenset = frozenset()


def alias_binding(value, bindings, star_imported):
    if value is None or not isinstance(value, (ast.Name, ast.Attribute)):
        return dynamic_binding()
    return resolve_reference(value, bindings, star_imported)


def scope_local_names(body):
    names = set()

    class Visitor(ast.NodeVisitor):
        def visit_FunctionDef(self, node):
            names.add(node.name)

        def visit_AsyncFunctionDef(self, node):
            names.add(node.name)

        def visit_ClassDef(self, node):
            names.add(node.name)

        def visit_Lambda(self, node):
            return

        def visit_Name(self, node):
            if isinstance(node.ctx, (ast.Store, ast.Del)):
                names.add(node.id)

        def visit_Import(self, node):
            for alias in node.names:
                names.add(alias.asname or alias.name.split(".")[0])

        def visit_ImportFrom(self, node):
            for alias in node.names:
                if alias.name != "*":
                    names.add(alias.asname or alias.name)

        def visit_ExceptHandler(self, node):
            if node.name:
                names.add(node.name)
            self.generic_visit(node)

        def visit_MatchAs(self, node):
            if node.name:
                names.add(node.name)
            self.generic_visit(node)

        def visit_MatchStar(self, node):
            if node.name:
                names.add(node.name)

        def visit_MatchMapping(self, node):
            if node.rest:
                names.add(node.rest)
            self.generic_visit(node)

    visitor = Visitor()
    for statement in body:
        visitor.visit(statement)
    return names


def function_parameter_names(node):
    result = set()
    args = node.args
    for argument in list(args.posonlyargs) + list(args.args) + list(args.kwonlyargs):
        result.add(argument.arg)
    if args.vararg is not None:
        result.add(args.vararg.arg)
    if args.kwarg is not None:
        result.add(args.kwarg.arg)
    return result


def is_literal_false(node):
    return isinstance(node, ast.Constant) and node.value is False


def dataclass_decorator_preserves_identity(decorator, bindings, star_imported):
    call = decorator if isinstance(decorator, ast.Call) else None
    target_expr = call.func if call is not None else decorator
    kind, _, target = resolve_reference(target_expr, bindings, star_imported)
    if kind != "qualified" or target != "dataclasses.dataclass":
        return False
    if call is None:
        return True
    if call.args:
        return False
    for keyword in call.keywords:
        if keyword.arg is None:
            return False
        if keyword.arg == "slots" and not is_literal_false(keyword.value):
            return False
    return True


def decorators_preserve_identity(decorators, bindings, star_imported):
    return all(
        dataclass_decorator_preserves_identity(item, bindings, star_imported)
        for item in decorators
    )


def has_star_import(body):
    class Visitor(ast.NodeVisitor):
        found = False

        def visit_FunctionDef(self, node):
            return

        visit_AsyncFunctionDef = visit_FunctionDef
        visit_ClassDef = visit_FunctionDef

        def visit_ImportFrom(self, node):
            self.found |= any(item.name == "*" for item in node.names)

    visitor = Visitor()
    for statement in body:
        visitor.visit(statement)
    return visitor.found


def has_control_transfer(body, kinds=(ast.Break, ast.Continue, ast.Return, ast.Raise)):
    class Visitor(ast.NodeVisitor):
        found = False

        def visit(self, node):
            if isinstance(node, kinds):
                self.found = True
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef, ast.Lambda)):
                super().visit(node)

    visitor = Visitor()
    for statement in body:
        visitor.visit(statement)
    return visitor.found


def has_call(body):
    class Visitor(ast.NodeVisitor):
        found = False

        def visit_Call(self, node):
            self.found = True

        def visit_FunctionDef(self, node):
            # Defaults and decorators execute at definition time, the body does not.
            for expression in node.decorator_list + node.args.defaults + node.args.kw_defaults:
                if expression is not None:
                    self.visit(expression)

        visit_AsyncFunctionDef = visit_FunctionDef

        def visit_Lambda(self, node):
            return

    visitor = Visitor()
    for statement in body:
        visitor.visit(statement)
    return visitor.found


def executed_call_roots(body):
    roots = set()

    class Visitor(ast.NodeVisitor):
        def visit_Call(self, node):
            expressions = list(node.args) + [keyword.value for keyword in node.keywords]
            if isinstance(node.func, ast.Attribute):
                expressions.append(node.func.value)
            for expression in expressions:
                name = dotted(expression)
                if name:
                    roots.add(name.split(".")[0])
            self.generic_visit(node)

        def visit_FunctionDef(self, node):
            for expression in node.decorator_list + node.args.defaults + node.args.kw_defaults:
                if expression is not None:
                    self.visit(expression)

        visit_AsyncFunctionDef = visit_FunctionDef

        def visit_Lambda(self, node):
            return

    visitor = Visitor()
    for statement in body:
        visitor.visit(statement)
    return roots


def escaping_writes(tree):
    """Explicit writes to outer scopes can be performed by an opaque call."""
    result = set()
    for block in ast.walk(tree):
        if not isinstance(block, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        declared = set()

        class Visitor(ast.NodeVisitor):
            def visit_Global(self, node):
                declared.update(node.names)

            visit_Nonlocal = visit_Global

            def visit_FunctionDef(self, node):
                return

            visit_AsyncFunctionDef = visit_FunctionDef
            visit_ClassDef = visit_FunctionDef

        visitor = Visitor()
        for statement in block.body:
            visitor.visit(statement)
        result.update(declared & scope_local_names(block.body))
    return result


def join_bindings(bindings, states):
    """Keep an identity only when every possible exit establishes that identity."""
    missing = object()
    keys = set().union(*(state.keys() for state, _ in states))
    joined = {}
    for name in keys:
        values = [state.get(name, missing) for state, _ in states]
        joined[name] = values[0] if all(value == values[0] for value in values) else dynamic_binding()
    bindings.clear()
    bindings.update(joined)
    return any(star for _, star in states)


def uncertain_bindings(bindings, body, extra=()):
    """Loop/exception entry can follow earlier writes; never assume a first iteration."""
    result = dict(bindings)
    for name in scope_local_names(body) | namespace_writes(body) | set(extra):
        result[name] = dynamic_binding()
    if has_star_import(body):
        result = {name: dynamic_binding() for name in result}
    return result


def process_branch(body, prefix, bindings, module, is_package, scope_kind, star_imported,
                   context):
    branch_bindings = dict(bindings)
    star = process_body(body, prefix, branch_bindings, module, is_package, scope_kind, star_imported,
                        context)
    return branch_bindings, star


def process_body(body, prefix, bindings, module, is_package, scope_kind, star_imported=False,
                 context=None):
    global hierarchy_incomplete
    context = context or ScopeContext()

    # Count future writes once per block, rather than repeatedly scanning its suffix.
    writes = [scope_local_names([statement]) | namespace_writes([statement], include_class_bodies=True)
              for statement in body]
    stars = [has_star_import([statement]) for statement in body]
    future = {}
    for names in writes:
        for name in names:
            future[name] = future.get(name, 0) + 1
    future_stars = sum(stars)
    for index, node in enumerate(body):
        for name in writes[index]:
            future[name] -= 1
            if not future[name]:
                del future[name]
        future_stars -= stars[index]

        # Calls can invoke explicit global/nonlocal writers via aliases or callbacks.
        # Do not execute source or guess which opaque call dispatches such a writer.
        if has_call([node]):
            for name in current_escaping_writes:
                bindings[name] = dynamic_binding()
            for name in executed_call_roots([node]):
                bound = bindings.get(name)
                if bound is not None and bound[0] == "qualified":
                    invalidate_namespace(name, bindings)
        for name in namespace_writes([node]):
            invalidate_namespace(name, bindings)

        def branch(statements, entry=None, star=None):
            return process_branch(statements, prefix, bindings if entry is None else entry,
                                  module, is_package, scope_kind,
                                  star_imported if star is None else star,
                                  replace(context,
                                          deferred_names=context.deferred_names | frozenset(future),
                                          deferred_star=context.deferred_star or bool(future_stars)))

        def deferred_environment():
            # Class locals are not lexical enclosing bindings of method bodies.
            if scope_kind == "class":
                return dict(context.method_bindings or {}), context.method_star
            free = dict(bindings)
            for name in set(future) | context.deferred_names | current_escaping_writes:
                free[name] = dynamic_binding()
            if future_stars or context.deferred_star:
                free = {name: dynamic_binding() for name in free}
            return free, star_imported or bool(future_stars) or context.deferred_star

        def global_environment(free, free_star):
            if scope_kind == "module":
                return dict(free), free_star
            globals_ = dict(context.global_bindings or {})
            for name in context.global_names:
                globals_[name] = bindings.get(name, dynamic_binding())
                if name in future or name in context.deferred_names or name in current_escaping_writes:
                    globals_[name] = dynamic_binding()
            return globals_, context.global_star

        if isinstance(node, ast.Import):
            for alias in node.names:
                bound = alias.asname or alias.name.split(".")[0]
                target = alias.name if alias.asname else alias.name.split(".")[0]
                bindings[bound] = dynamic_binding() if namespace_key(target) in bindings else qualified_binding(target)

        elif isinstance(node, ast.ImportFrom):
            absolute = absolute_from(module, is_package, node.level, node.module)
            for alias in node.names:
                if alias.name == "*":
                    star_imported = True
                    # Earlier explicit aliases can be overwritten as well.
                    bindings.update({name: dynamic_binding() for name in bindings})
                    continue
                bound = alias.asname or alias.name
                target = (
                    None
                    if absolute is None
                    else (absolute + "." if absolute else "") + alias.name
                )
                bindings[bound] = (qualified_binding(target) if target and namespace_key(target) not in bindings
                                   else dynamic_binding())

        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if scope_kind == "class" and node.name not in context.outer_names:
                descriptor, abstraction = method_descriptor(node, bindings, star_imported)
                context.members.append(class_member(node, prefix, "METHOD", node.name, descriptor, abstraction))
            function_qualified = child_name(prefix, node.name)
            function_bindings, function_star = deferred_environment()
            global_bindings, global_star = global_environment(function_bindings, function_star)
            declared_globals, declared_nonlocals = scope_declarations(node.body)
            for name in declared_globals:
                function_bindings[name] = global_bindings.get(name, dynamic_binding())
            local_names = (scope_local_names(node.body) | function_parameter_names(node)) - declared_globals - declared_nonlocals
            for local_name in local_names:
                function_bindings[local_name] = dynamic_binding()
            process_body(
                node.body,
                function_qualified + ".<locals>",
                function_bindings,
                module,
                is_package,
                "function",
                function_star,
                ScopeContext(global_bindings=global_bindings, global_star=global_star,
                             outer_names=frozenset(declared_globals | declared_nonlocals),
                             global_names=frozenset(declared_globals)),
            )
            bindings[node.name] = dynamic_binding()

        elif isinstance(node, ast.ClassDef):
            qualified = child_name(prefix, node.name)
            key = definition_key(current_rel, node, qualified)
            bases = [resolve_base(base, bindings, star_imported) for base in node.bases]
            if any(
                base["definitionCandidate"] is None
                and base["qualifiedCandidate"] is None
                and base["builtinCandidate"] is None
                for base in bases
            ):
                hierarchy_incomplete = True
                diagnostics.append(
                    {
                        "kind": "EVIDENCE_INCOMPLETE",
                        "path": current_rel,
                        "line": getattr(node, "lineno", 0) or 0,
                        "message": "dynamic or shadowed Python base prevents complete hierarchy resolution: "
                        + qualified,
                    }
                )
            members = []
            classes.append(
                {
                    "definitionKey": key,
                    "path": current_rel,
                    "moduleName": module,
                    "qualifiedName": qualified,
                    "line": getattr(node, "lineno", 1) or 1,
                    "endLine": getattr(node, "end_lineno", None)
                    or getattr(node, "lineno", 1)
                    or 1,
                    "bases": bases,
                    "members": members,
                }
            )
            class_bindings = dict(bindings)
            method_bindings, method_star = deferred_environment()
            global_bindings, global_star = global_environment(method_bindings, method_star)
            declared_globals, declared_nonlocals = scope_declarations(node.body)
            for name in declared_globals:
                class_bindings[name] = global_bindings.get(name, dynamic_binding())
            process_body(
                node.body,
                qualified,
                class_bindings,
                module,
                is_package,
                "class",
                star_imported,
                ScopeContext(global_bindings=global_bindings, global_star=global_star,
                             method_bindings=method_bindings, method_star=method_star, members=members,
                             outer_names=frozenset(declared_globals | declared_nonlocals),
                             global_names=frozenset(declared_globals)),
            )
            # Executed class blocks can write outer bindings. Interprocedural
            # effects are not inferred from a private copied class namespace.
            for name in (declared_globals | declared_nonlocals) & scope_local_names(node.body):
                bindings[name] = dynamic_binding()
            # A class body executes now. Its module/object mutations affect later
            # outer lookups even though ordinary class-local names do not escape.
            # Propagate only namespace invalidation markers after the body; doing
            # so before entry would discard valid earlier descriptor identities.
            bindings.update({name: value for name, value in class_bindings.items()
                             if name.startswith("\0namespace-unknown:")})
            if decorators_preserve_identity(node.decorator_list, bindings, star_imported):
                bindings[node.name] = definition_binding(key, qualified)
            else:
                bindings[node.name] = dynamic_binding()

        elif isinstance(node, ast.Assign):
            binding = alias_binding(node.value, bindings, star_imported)
            if scope_kind == "class":
                names = dict.fromkeys(name for target in node.targets for name in assigned_names(target))
                for name in names:
                    if name not in context.outer_names:
                        context.members.append(class_member(node, prefix, "ATTRIBUTE", name))
            for target in node.targets:
                for name in assigned_names(target):
                    bindings[name] = binding

        elif isinstance(node, ast.AnnAssign):
            binding = alias_binding(node.value, bindings, star_imported)
            if scope_kind == "class":
                for name in dict.fromkeys(assigned_names(node.target)):
                    if name not in context.outer_names:
                        context.members.append(class_member(node, prefix, "ATTRIBUTE", name))
            for name in assigned_names(node.target):
                bindings[name] = binding

        elif isinstance(node, ast.AugAssign):
            for name in assigned_names(node.target):
                bindings[name] = dynamic_binding()

        elif isinstance(node, ast.Delete):
            for target in node.targets:
                for name in assigned_names(target):
                    # Keep explicit uncertainty so lookup cannot reuse the old
                    # definition or turn an unbound local into a platform root.
                    bindings[name] = dynamic_binding()

        elif isinstance(node, (ast.For, ast.AsyncFor)):
            for name in scope_local_names([ast.Expr(value=node.iter)]):
                bindings[name] = dynamic_binding()
            initial = (dict(bindings), star_imported)
            entry = uncertain_bindings(bindings, node.body, assigned_names(node.target))
            body_state = branch(node.body, entry, star_imported or has_star_import(node.body))
            states = [initial, body_state]
            if has_control_transfer(node.body):
                states.append((entry, star_imported or has_star_import(node.body)))
            star_imported = join_bindings(bindings, states)
            else_state = branch(node.orelse)
            exits = [(dict(bindings), star_imported), else_state] if has_control_transfer(node.body, (ast.Break,)) else [else_state]
            star_imported = join_bindings(bindings, exits)

        elif isinstance(node, ast.While):
            for name in scope_local_names([ast.Expr(value=node.test)]):
                bindings[name] = dynamic_binding()
            initial = (dict(bindings), star_imported)
            entry = uncertain_bindings(bindings, node.body)
            body_state = branch(node.body, entry, star_imported or has_star_import(node.body))
            if isinstance(node.test, ast.Constant) and node.test.value is False:
                # Source declarations in the body were still observed above.
                body_state = (dict(bindings), star_imported)
            states = [initial, body_state]
            if has_control_transfer(node.body) and not is_literal_false(node.test):
                states.append((entry, star_imported or has_star_import(node.body)))
            star_imported = join_bindings(bindings, states)
            else_state = branch(node.orelse)
            exits = [(dict(bindings), star_imported), else_state] if has_control_transfer(node.body, (ast.Break,)) else [else_state]
            star_imported = join_bindings(bindings, exits)

        elif isinstance(node, ast.If):
            # Assignment expressions in a condition are outside the simple-alias
            # subset, but must not leave an older complete binding behind.
            for name in scope_local_names([ast.Expr(value=node.test)]):
                bindings[name] = dynamic_binding()
            yes = branch(node.body)
            no = branch(node.orelse)
            if isinstance(node.test, ast.Constant) and isinstance(node.test.value, bool):
                states = [yes if node.test.value else no]
            else:
                states = [yes, no]
            star_imported = join_bindings(bindings, states)

        elif isinstance(node, (ast.With, ast.AsyncWith)):
            entry = dict(bindings)
            for item in node.items:
                if item.optional_vars is not None:
                    for name in assigned_names(item.optional_vars):
                        entry[name] = dynamic_binding()
            body_state = branch(node.body, entry)
            # An opaque context manager may suppress a partial-body exception.
            states = [(dict(entry), star_imported), body_state]
            if has_control_transfer(node.body) or has_call(node.body):
                states.append((uncertain_bindings(entry, node.body), star_imported or has_star_import(node.body)))
            star_imported = join_bindings(bindings, states)

        elif isinstance(node, (ast.Try, getattr(ast, "TryStar", ast.Try))):
            initial = dict(bindings)
            body_state = branch(node.body)
            normal = branch(node.orelse, body_state[0], body_state[1])
            states = [normal]
            # A handler may run after any prefix of the protected body.
            exceptional = uncertain_bindings(initial, node.body)
            for handler in node.handlers:
                entry = dict(exceptional)
                if handler.name:
                    entry[handler.name] = dynamic_binding()
                handler_state = branch(handler.body, entry, star_imported or has_star_import(node.body))
                if handler.name:
                    handler_state[0][handler.name] = dynamic_binding()
                states.append(handler_state)
            if node.finalbody:
                # Finally is also observed on unhandled exceptional exits. The
                # completed protected-body state alone would invent its parents.
                protected = node.body + node.orelse + [statement for handler in node.handlers for statement in handler.body]
                states.append((uncertain_bindings(initial, protected), star_imported or has_star_import(protected)))
            star_imported = join_bindings(bindings, states)
            # Finally runs on every exit; its definite writes supersede uncertainty.
            star_imported = process_body(node.finalbody, prefix, bindings, module, is_package,
                                         scope_kind, star_imported,
                                         replace(context, deferred_names=context.deferred_names | frozenset(future),
                                                 deferred_star=context.deferred_star or bool(future_stars)))

        elif hasattr(ast, "Match") and isinstance(node, ast.Match):
            states = [(dict(bindings), star_imported)]
            for case in node.cases:
                entry = uncertain_bindings(bindings, [], scope_local_names([case.pattern]))
                states.append(branch(case.body, entry))
            star_imported = join_bindings(bindings, states)

        else:
            # Unsupported binding expressions (e.g. a standalone walrus) must
            # invalidate the affected name instead of silently preserving it.
            for name in scope_local_names([node]):
                bindings[name] = dynamic_binding()

    return star_imported


source_modules = {module_name(path) for path in paths}
for current_rel in paths:
    module = module_name(current_rel)
    is_package = pathlib.PurePosixPath(current_rel).name == "__init__.py"
    path = root / pathlib.PurePosixPath(current_rel)
    try:
        with tokenize.open(str(path)) as source:
            text = source.read()
        tree = ast.parse(text, filename=current_rel, type_comments=True)
    except (SyntaxError, UnicodeError) as failure:
        hierarchy_incomplete = True
        line = getattr(failure, "lineno", 0) or 0
        diagnostics.append(
            {
                "kind": "PARSE_ERROR",
                "path": current_rel,
                "line": line,
                "message": str(failure)[:4096],
            }
        )
        continue

    current_escaping_writes = escaping_writes(tree)
    process_body(tree.body, module, {}, module, is_package, "module")

if runtime_executable_snapshot() != runtime_executable_before:
    raise RuntimeError("Python runtime executable changed during AST extraction")
if runtime_configuration_snapshot() != runtime_configuration_before:
    raise RuntimeError("Python runtime configuration changed during AST extraction")
runtime_modules_after = runtime_module_snapshot()
for runtime_module_name, snapshot in runtime_modules_before.items():
    if runtime_modules_after.get(runtime_module_name) != snapshot:
        raise RuntimeError("Python module backing metadata/bytes changed during AST extraction: " + runtime_module_name)

json.dump(
    {
        "pythonVersion": ".".join(map(str, sys.version_info[:3])),
        "runtimeExecutable": runtime_executable_before,
        "runtimeContext": {"configuration": runtime_configuration_before,
                           "modules": list(runtime_modules_after.values())},
        "hierarchyIncomplete": hierarchy_incomplete,
        "classes": classes,
        "diagnostics": diagnostics,
    },
    sys.stdout,
    sort_keys=True,
    separators=(",", ":"),
)
