"""Guarded native class creation from inert source namespaces.

Never execute a captured module, class suite, default, annotation or method body.
Only trusted stdlib/type descriptors participate in the native construction.
"""
import abc
import ast
import inspect
import types


class OutsideFragment(ValueError):
    pass


def project(tree):
    aliases={name:value for name,value in dict(object=object,type=type,staticmethod=staticmethod,classmethod=classmethod).items()}
    built_classes={};result={}
    primitives={'int','str','bool','float','bytes','object'}
    hooks={'__init_subclass__','__class_getitem__','__getattribute__','__getattr__','__setattr__','__get__','__set__','__delete__'}
    metadata={name for base in type.__mro__ for name,value in vars(base).items() if hasattr(type(value),'__set__') or hasattr(type(value),'__delete__')}
    metadata|={'__slots__','__metaclass__','__bases__','__classcell__'}
    # Obtain metaclass-managed namespace names from the selected runtime itself.
    abc_metadata=set(vars(abc.ABCMeta('_ProjectionMetadataProbe',(),{})))|{'__isabstractmethod__'}
    abc_members={'ABCMeta','abstractmethod','abstractstaticmethod','abstractclassmethod'}
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
    def fresh_function(node):
        def stub(*args,**kwargs):return None
        async def async_stub(*args,**kwargs):return None
        code=async_stub.__code__ if isinstance(node,ast.AsyncFunctionDef) else stub.__code__
        value=types.FunctionType(code,{'__builtins__':{}},node.name)
        for expression in reversed(node.decorator_list):
            decorator=resolve(expression)
            if decorator not in decorators:raise OutsideFragment('unsupported descriptor')
            try:value=decorator(value)
            except (AttributeError,TypeError) as error:raise OutsideFragment('invalid descriptor ordering') from error
        return value
    try:
        for node in tree.body:
            if inert(node):continue
            if isinstance(node,ast.Import):
                for entry in node.names:
                    name=entry.asname or entry.name
                    if entry.name!='abc' or name in aliases or name in built_classes:raise OutsideFragment('unsupported/rebound module import')
                    aliases[name]=abc
                continue
            if isinstance(node,ast.ImportFrom):
                if node.level or node.module!='abc':raise OutsideFragment('unsupported module import')
                for entry in node.names:
                    name=entry.asname or entry.name
                    if entry.name not in abc_members or name in aliases or name in built_classes:raise OutsideFragment('unsupported/rebound imported identity')
                    aliases[name]=getattr(abc,entry.name)
                continue
            if not isinstance(node,ast.ClassDef):raise OutsideFragment('module execution outside fragment')
            if node.name in aliases or node.name in primitives or node.name in built_classes or node.decorator_list:
                raise OutsideFragment('redefined/decorated class')
            bases=[]
            for base in node.bases:
                if not isinstance(base,ast.Name):raise OutsideFragment('non-name or external base')
                if base.id=='object':bases.append(object)
                elif base.id in built_classes:bases.append(built_classes[base.id])
                else:raise OutsideFragment('unobserved base')
            keywords={}
            for keyword in node.keywords:
                if keyword.arg!='metaclass' or keywords:raise OutsideFragment('unsupported class keyword')
                meta=resolve(keyword.value)
                if meta not in (type,abc.ABCMeta):raise OutsideFragment('custom metaclass')
                keywords['metaclass']=meta
            uses_abc=keywords.get('metaclass') is abc.ABCMeta or any(isinstance(base,abc.ABCMeta) for base in bases)
            namespace={'__module__':'native_source_projection','__qualname__':node.name};seen=set()
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
            if '__eq__' in seen and '__hash__' not in seen:raise OutsideFragment('implicit hash suppression')
            native=types.new_class(node.name,tuple(bases),keywords,lambda ns:ns.update(namespace))
            built_classes[node.name]=native
            result[node.name]=dict(abstraction='ABSTRACT' if inspect.isabstract(native) else 'CONCRETE',
                abstractNames=sorted(getattr(native,'__abstractmethods__',())),mro=[c.__name__ for c in native.__mro__])
        return dict(complete=True,classes=result,reason='')
    except (OutsideFragment,TypeError) as error:
        return dict(complete=False,classes={},reason=str(error))
