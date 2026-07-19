#!/usr/bin/env python3
"""
Python structural extraction script.
Reads .py files from a repository directory, emits extraction.json to stdout.

Usage: python extract_python.py <repo_dir>
"""
import ast
import json
import os
import sys
from pathlib import Path


def extract_from_repo(repo_dir):
    """Walk repo_dir, parse every .py file, collect structural model."""
    types = []
    seen = {}
    repo_path = Path(repo_dir).resolve()
    files = sorted(repo_path.rglob("*.py"), key=lambda p: str(p))

    for filepath in files:
        try:
            source = filepath.read_text(encoding="utf-8-sig")
        except (OSError, UnicodeDecodeError):
            continue
        try:
            tree = ast.parse(source, filename=str(filepath))
        except SyntaxError:
            continue

        visitor = ModuleVisitor(str(repo_path), str(filepath))
        visitor.visit(tree)

        for t in visitor.types:
            fqn = t["qualifiedName"]
            if fqn not in seen:
                seen[fqn] = t
                types.append(t)

    result = {
        "schemaVersion": "1.0",
        "projectName": repo_path.name,
        "repository": str(repo_path),
        "generatedAt": "",
        "sourceRoots": [str(repo_path)],
        "types": types,
        "diagnostics": [{"severity": "INFO", "code": "PYTHON_EXTRACTION",
                         "message": f"Extracted {len(types)} types from {len(files)} files"}]
    }
    return result


class ModuleVisitor(ast.NodeVisitor):
    def __init__(self, repo_dir, filepath):
        self.repo_dir = repo_dir
        self.filepath = filepath
        self.types = []
        self._current_cls = []

    def _qualname(self, name):
        if self._current_cls:
            return ".".join(self._current_cls + [name])
        return name

    def _kind(self, node):
        bases = [self._base_name(b) for b in node.bases]
        keywords = {kw.arg: kw.value for kw in node.keywords if kw.arg}
        # Heuristic: ABC, Protocol, or metaclass=ABCMeta => interface
        if any(b in ("ABC", "ABCMeta", "Protocol") for b in bases):
            return "interface"
        if "metaclass" in keywords:
            mc = keywords["metaclass"]
            if isinstance(mc, ast.Name) and mc.id == "ABCMeta":
                return "interface"
            if isinstance(mc, ast.Attribute) and mc.attr == "ABCMeta":
                return "interface"
        return "class"

    def _base_name(self, base):
        if isinstance(base, ast.Name):
            return base.id
        if isinstance(base, ast.Attribute):
            return base.attr
        return ""

    def _vis(self, node, default="public"):
        # Python convention: __x = private, _x = protected, x = public
        pass

    def _method_vis(self, name):
        if name.startswith("__"):
            return "private"
        if name.startswith("_"):
            return "protected"
        return "public"

    def _field_vis(self, name):
        if name.startswith("__"):
            return "private"
        if name.startswith("_"):
            return "protected"
        return "public"

    def _is_abstract(self, node):
        for dec in getattr(node, "decorator_list", []):
            if isinstance(dec, ast.Name) and dec.id == "abstractmethod":
                return True
            if isinstance(dec, ast.Attribute) and dec.attr == "abstractmethod":
                return True
        if isinstance(node, ast.ClassDef):
            return False
        return False

    def _is_static(self, node):
        for dec in getattr(node, "decorator_list", []):
            if isinstance(dec, ast.Name) and dec.id in ("staticmethod", "classmethod"):
                return True
            if isinstance(dec, ast.Attribute) and dec.attr in ("staticmethod", "classmethod"):
                return True
        return False

    def _is_constructor(self, node):
        return isinstance(node, ast.FunctionDef) and node.name == "__init__"

    def _return_type(self, returns_node):
        if returns_node is None:
            return "None"
        if isinstance(returns_node, ast.Name):
            return returns_node.id
        if isinstance(returns_node, ast.Constant):
            return "None"
        return "unknown"

    def _param_list(self, node):
        params = []
        for arg in node.args.args:
            if arg.arg == "self" or arg.arg == "cls":
                continue
            ptype = "unknown"
            if arg.annotation:
                if isinstance(arg.annotation, ast.Name):
                    ptype = arg.annotation.id
                elif isinstance(arg.annotation, ast.Attribute):
                    ptype = arg.annotation.attr
            params.append({"name": arg.arg, "type": ptype})
        return params

    def visit_ClassDef(self, node):
        qname = self._qualname(node.name)
        self._current_cls.append(node.name)

        fields = []
        executables = []
        superclass = None
        interfaces = []

        abc_names = {"object", "ABC", "ABCMeta", "Protocol", "Exception", "BaseException"}
        for base in node.bases:
            bname = self._base_name(base)
            if bname in abc_names:
                continue
            if superclass is None:
                superclass = bname
            else:
                interfaces.append(bname)

        is_abstract = any(
            isinstance(item, ast.FunctionDef) and self._is_abstract(item)
            for item in node.body
        ) or self._is_abstract(node)

        for item in node.body:
            if isinstance(item, ast.FunctionDef):
                ex = {
                    "name": item.name,
                    "returnType": self._return_type(item.returns),
                    "visibility": self._method_vis(item.name),
                    "constructor": self._is_constructor(item),
                    "staticExecutable": self._is_static(item),
                    "abstractExecutable": self._is_abstract(item),
                    "parameters": self._param_list(item),
                    "line": item.lineno
                }
                executables.append(ex)
            elif isinstance(item, ast.Assign):
                for target in item.targets:
                    if isinstance(target, ast.Name):
                        fname = target.id
                        if fname.startswith("_") and "__" not in fname:
                            fvis = "protected"
                        elif fname.startswith("__"):
                            fvis = "private"
                        else:
                            fvis = "public"
                        fields.append({
                            "name": fname,
                            "type": "unknown",
                            "visibility": fvis,
                            "staticField": False,
                            "finalField": False,
                            "line": item.lineno
                        })
            elif isinstance(item, ast.AnnAssign) and isinstance(item.target, ast.Name):
                fname = item.target.id
                fvis = self._field_vis(fname)
                ftype = "unknown"
                if item.annotation:
                    if isinstance(item.annotation, ast.Name):
                        ftype = item.annotation.id
                    elif isinstance(item.annotation, ast.Attribute):
                        ftype = item.annotation.attr
                fields.append({
                    "name": fname,
                    "type": ftype,
                    "visibility": fvis,
                    "staticField": False,
                    "finalField": False,
                    "line": item.lineno
                })

        t = {
            "qualifiedName": qname,
            "simpleName": node.name,
            "kind": self._kind(node),
            "superClass": superclass,
            "interfaces": interfaces,
            "fields": fields,
            "executables": executables,
            "abstractType": is_abstract,
            "finalType": False,
            "sourceFile": str(Path(self.filepath).relative_to(self.repo_dir)) if self.repo_dir else self.filepath,
            "line": node.lineno
        }
        self.types.append(t)

        self.generic_visit(node)
        self._current_cls.pop()


def main():
    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: extract_python.py <repo_dir>"}), file=sys.stderr)
        sys.exit(1)
    repo_dir = sys.argv[1]
    result = extract_from_repo(repo_dir)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
