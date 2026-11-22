"""Guarded native class creation from inert source namespaces.

Never execute a captured module, class suite, default, annotation or method body.
Only trusted stdlib/type descriptors participate in the native construction.
"""
import abc
import builtins
import ast
import inspect
import types


class OutsideFragment(ValueError):
    pass


def project(tree, import_resolver=None, module_identity='', native_registry=None):
    postponed_annotations=False
    aliases={name:value for name,value in dict(object=object,type=type,staticmethod=staticmethod,classmethod=classmethod).items()}
    built_classes={};result={};exports={}
    native_names,source_methods=({}, {}) if native_registry is None else native_registry
    primitives={'int','str','bool','float','bytes','object'}
    hooks={'__init_subclass__','__class_getitem__','__getattribute__','__getattr__','__setattr__','__get__','__set__','__delete__'}
    metadata={name for base in type.__mro__ for name,value in vars(base).items() if hasattr(type(value),'__set__') or hasattr(type(value),'__delete__')}
    metadata|={'__slots__','__metaclass__','__bases__','__classcell__'}
    # Obtain metaclass-managed namespace names from the selected runtime itself.
    abc_metadata=set(vars(abc.ABCMeta('_ProjectionMetadataProbe',(),{})))|{'__isabstractmethod__'}
    abc_members={'ABCMeta','abstractmethod','abstractstaticmethod','abstractclassmethod'}
    # Inert module constants do not enter the constructor/decorator authority map.
    # Reserve future declarations as well as current bindings: later class/import
    # statements must never silently replace a constant admitted earlier.
    module_constants=set()
    declared_classes={node.name for node in tree.body if isinstance(node,ast.ClassDef)}
    constant_reserved=set(vars(builtins))|abc_members|metadata|abc_metadata|{'abc'}
    decorators={abc.abstractmethod,abc.abstractstaticmethod,abc.abstractclassmethod,staticmethod,classmethod}
    def inert(node):
        return isinstance(node,ast.Pass) or isinstance(node,ast.Expr) and isinstance(node.value,ast.Constant) and isinstance(node.value.value,str)
    def literal(node):
        return isinstance(node,ast.Constant) or isinstance(node,(ast.List,ast.Tuple)) and all(literal(e) for e in node.elts)
    def annotation(node):
        return node is None or isinstance(node,ast.Constant) or isinstance(node,ast.Name) and node.id in primitives
    def resolve(node):
        if isinstance(node,ast.Name) and node.id in aliases:return aliases[node.id]
        if isinstance(node,ast.Attribute) and isinstance(node.value,ast.Name) and aliases.get(node.value.id) is abc and node.attr in abc_members:
            return getattr(abc,node.attr)
        raise OutsideFragment('unresolved constructor/decorator identity')
    def method_facts(native,keys):
        rows=[]
        for key in sorted(keys):
            descriptor=vars(native)[key]
            if isinstance(descriptor,staticmethod):kind='STATIC';function=descriptor.__func__
            elif isinstance(descriptor,classmethod):kind='CLASS';function=descriptor.__func__
            else:kind='INSTANCE';function=descriptor
            if not isinstance(function,types.FunctionType):raise OutsideFragment('unobserved method transformation')
            targets=[];reason=''
            for ancestor in native.__mro__[1:]:
                if key not in vars(ancestor):continue
                owner=native_names.get(ancestor)
                if owner is None:reason='ancestor slot outside observed source population';break
                if key not in source_methods[ancestor]:reason='method/attribute ancestor slot collision';break
                targets.append(dict(owner=owner,name=key))
            annotations=inspect.get_annotations(function,eval_str=False)
            token=None
            if 'return' in annotations:
                value=annotations['return']
                if value is None:token='None'
                elif any(value is getattr(builtins,name) for name in primitives):token=value.__name__
            rows.append(dict(name=key,descriptorKind=kind,
                overrideKnowledge='UNKNOWN' if reason else 'KNOWN',overrides=[] if reason else targets,
                overrideReason=reason,returnKnowledge='KNOWN' if token is not None else 'UNKNOWN',
                returnToken=token,returnReason='' if token is not None else 'no supported bound builtin return annotation'))
        return rows

    def fresh_function(node):
        def stub(*args,**kwargs):return None
        async def async_stub(*args,**kwargs):return None
        code=async_stub.__code__ if isinstance(node,ast.AsyncFunctionDef) else stub.__code__
        value=types.FunctionType(code,{'__builtins__':{}},node.name)
        # Reconstruct only inert, closed annotation metadata. No captured expression
        # is evaluated, and string annotations are never passed to eval.
        if postponed_annotations and node.returns is not None:
            # The compiler stores strings under this future feature. Replacing
            # them with bound builtins would fabricate runtime return identity.
            value.__annotations__={'return':ast.unparse(node.returns)}
        elif isinstance(node.returns,ast.Name) and node.returns.id in primitives:
            value.__annotations__={'return':aliases.get(node.returns.id,getattr(builtins,node.returns.id))}
        elif isinstance(node.returns,ast.Constant):value.__annotations__={'return':node.returns.value}

        for expression in reversed(node.decorator_list):
            decorator=resolve(expression)
            if decorator not in decorators:raise OutsideFragment('unsupported descriptor')
            try:value=decorator(value)
            except (AttributeError,TypeError) as error:raise OutsideFragment('invalid descriptor ordering') from error
        return value
    try:
        for node in tree.body:
            if inert(node):continue
            if isinstance(node,ast.Assign):
                if (len(node.targets)!=1 or not isinstance(node.targets[0],ast.Name)
                        or not isinstance(node.value,ast.Constant)
                        or type(node.value.value) not in (type(None),bool,int,float,complex,str,bytes)):
                    raise OutsideFragment('non-primitive module assignment')
                name=node.targets[0].id
                if (name.startswith('__') or name in constant_reserved or name in aliases
                        or name in declared_classes or name in module_constants):
                    raise OutsideFragment('shadowed or namespace-sensitive module constant')
                module_constants.add(name)
                continue
            if isinstance(node,ast.Import):
                for entry in node.names:
                    name=entry.asname or entry.name
                    if entry.name!='abc' or name in aliases or name in built_classes or name in module_constants:raise OutsideFragment('unsupported/rebound module import')
                    aliases[name]=abc
                    exports[name]=abc
                continue
            if isinstance(node,ast.ImportFrom):
                if not node.level and node.module=='__future__':
                    if (len(node.names)!=1 or node.names[0].name!='annotations'
                            or node.names[0].asname is not None or postponed_annotations
                            or 'annotations' in module_constants or 'annotations' in declared_classes):
                        raise OutsideFragment('unsupported/rebound future feature')
                    # Validate placement and compiler feature semantics without
                    # executing the captured module or any annotation expression.
                    try:compile(tree,'<native-source-projection>','exec',dont_inherit=True)
                    except (SyntaxError,ValueError,TypeError) as error:
                        raise OutsideFragment('compiler rejects future feature') from error
                    postponed_annotations=True
                    module_constants.add('annotations')
                    continue
                trusted_abc=not node.level and node.module=='abc'
                imported=None
                if not trusted_abc:
                    if import_resolver is None:raise OutsideFragment('unsupported module import')
                    imported=import_resolver(node)
                for entry in node.names:
                    name=entry.asname or entry.name
                    if name in aliases or name in built_classes or name in module_constants:raise OutsideFragment('unsupported/rebound imported identity')
                    if trusted_abc:
                        if entry.name not in abc_members:raise OutsideFragment('unsupported imported abc identity')
                        value=getattr(abc,entry.name)
                    else:
                        if entry.name=='*' or entry.name not in imported:raise OutsideFragment('unobserved imported identity')
                        value=imported[entry.name]
                    aliases[name]=value;exports[name]=value
                continue
            if not isinstance(node,ast.ClassDef):raise OutsideFragment('module execution outside fragment')
            if node.name in aliases or node.name in primitives or node.name in built_classes or node.decorator_list:
                raise OutsideFragment('redefined/decorated class')
            bases=[]
            for base in node.bases:
                if not isinstance(base,ast.Name):raise OutsideFragment('non-name or external base')
                if base.id=='object':bases.append(object)
                elif base.id in built_classes:bases.append(built_classes[base.id])
                elif base.id in aliases and aliases[base.id] in native_names:bases.append(aliases[base.id])
                else:raise OutsideFragment('unobserved base')
            keywords={}
            for keyword in node.keywords:
                if keyword.arg!='metaclass' or keywords:raise OutsideFragment('unsupported class keyword')
                meta=resolve(keyword.value)
                if meta not in (type,abc.ABCMeta):raise OutsideFragment('custom metaclass')
                keywords['metaclass']=meta
            uses_abc=keywords.get('metaclass') is abc.ABCMeta or any(isinstance(base,abc.ABCMeta) for base in bases)
            namespace={'__module__':'native_source_projection','__qualname__':node.name};seen=set();method_keys=set()
            for member in node.body:
                if inert(member):continue
                if isinstance(member,(ast.FunctionDef,ast.AsyncFunctionDef)):
                    name=member.name
                    if name in hooks or any(isinstance(x,ast.ClassDef) for x in ast.walk(member)):
                        raise OutsideFragment('custom hook or method-local class')
                    defaults=member.args.defaults+[x for x in member.args.kw_defaults if x is not None]
                    args=member.args.posonlyargs+member.args.args+member.args.kwonlyargs+[x for x in (member.args.vararg,member.args.kwarg) if x is not None]
                    if any(not literal(x) for x in defaults) or any(not annotation(x.annotation) for x in args) or not annotation(member.returns):
                        raise OutsideFragment('executable default/annotation')
                    value=fresh_function(member)
                elif isinstance(member,ast.Assign) and len(member.targets)==1 and isinstance(member.targets[0],ast.Name) and literal(member.value):
                    name=member.targets[0].id;value=ast.literal_eval(member.value)
                else:raise OutsideFragment('unsupported class namespace statement')
                if name in aliases or name in primitives or name in metadata or uses_abc and name in abc_metadata:raise OutsideFragment('shadowed or layout-changing namespace')
                private_owner=node.name.lstrip('_')
                key='_'+private_owner+name if private_owner and name.startswith('__') and not name.endswith('__') else name
                if key in seen:raise OutsideFragment('redefined runtime member')
                seen.add(key);namespace[key]=value
                if isinstance(member,(ast.FunctionDef,ast.AsyncFunctionDef)):method_keys.add(key)
            if '__eq__' in seen and '__hash__' not in seen:raise OutsideFragment('implicit hash suppression')
            native=types.new_class(node.name,tuple(bases),keywords,lambda ns:ns.update(namespace))
            built_classes[node.name]=native
            native_names[native]=(module_identity+'.' if module_identity else '')+node.name
            source_methods[native]=method_keys;exports[node.name]=native
            result[node.name]=dict(abstraction='ABSTRACT' if inspect.isabstract(native) else 'CONCRETE',
                abstractNames=sorted(getattr(native,'__abstractmethods__',())),mro=[c.__name__ for c in native.__mro__],
                methods=method_facts(native,method_keys))
        answer=dict(complete=True,classes=result,reason='')
        if import_resolver is not None:answer['_exports']=exports
        return answer
    except (OutsideFragment,TypeError) as error:
        return dict(complete=False,classes={},reason=str(error))


def project_captured_modules(records, absolute_import):
    """Project a closed, acyclic source graph without importing captured code.

    Each record owns one already-captured, compiler-validated AST and source hash.
    The cache is invocation-local; no module or class survives another source set
    or selected interpreter. Only explicit from-imported namespace identities are
    exported. Unobserved dependencies, namespace packages and cycles fail closed.
    """
    by_module={}
    for record in records:
        by_module.setdefault(record['module'],[]).append(record)
    registry=({},{});cache={};active=set()
    shadowed_abc=any(name=='abc' or name.startswith('abc.') for name in by_module)
    def failed(reason):return dict(complete=False,classes={},reason=reason)
    def observe(record):
        identity=(record['path'],record['sha256'])
        if identity in cache:return cache[identity]
        if identity in active:raise OutsideFragment('cyclic captured-module initialization')
        if len(by_module[record['module']])!=1:
            return failed('ambiguous captured module/package identity')
        active.add(identity)
        try:
            # Importing a dotted module initializes each parent package first.
            parts=record['module'].split('.')
            for index in range(1,len(parts)):
                parent='.'.join(parts[:index])
                candidates=by_module.get(parent,[])
                if len(candidates)!=1 or not candidates[0]['is_package']:
                    raise OutsideFragment('unobserved or ambiguous package initializer')
                if not observe(candidates[0])['complete']:
                    raise OutsideFragment('unqualified package initializer')
            if shadowed_abc and any(
                    isinstance(node,ast.Import) and any(entry.name=='abc' for entry in node.names)
                    or isinstance(node,ast.ImportFrom) and node.module=='abc' and not node.level
                    for node in record['tree'].body):
                raise OutsideFragment('project abc namespace prevents standard-library identity proof')
            def resolve_import(node):
                module=absolute_import(record['module'],record['is_package'],node.level,node.module)
                candidates=by_module.get(module,[])
                if len(candidates)!=1:raise OutsideFragment('unobserved or ambiguous captured import')
                result=observe(candidates[0])
                if not result['complete']:raise OutsideFragment('imported module outside declarative fragment')
                return result['_exports']
            result=project(record['tree'],resolve_import,'' if record['module']=='<root>' else record['module'],registry)
            cache[identity]=result
            return result
        except (OutsideFragment,RecursionError) as error:
            result=failed(str(error));cache[identity]=result;return result
        finally:active.remove(identity)
    return {record['path']:observe(record) for record in records}
