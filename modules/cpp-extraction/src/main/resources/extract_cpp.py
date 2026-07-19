#!/usr/bin/env python3
"""
C++ structural extraction using libclang.
Reads .cpp/.h/.hpp files from a repository directory, emits extraction.json to stdout.

Requirements: pip install libclang

Usage: python extract_cpp.py <repo_dir>
"""
import json
import os
import sys
from pathlib import Path

try:
    from clang import cindex
    from clang.cindex import CursorKind, AccessSpecifier, TypeKind
except ImportError:
    print(json.dumps({"error": "libclang not installed. Run: pip install libclang"}), file=sys.stderr)
    sys.exit(1)


CPP_EXTENSIONS = {".cpp", ".cc", ".cxx", ".h", ".hpp", ".hxx"}


def extract_from_repo(repo_dir):
    repo_path = Path(repo_dir).resolve()
    files = sorted(
        [p for p in repo_path.rglob("*") if p.suffix.lower() in CPP_EXTENSIONS],
        key=lambda p: str(p),
    )
    if not files:
        return _empty_result(repo_path, "No C++ source files found")

    index = cindex.Index.create()
    types = []
    seen_fqns = {}

    for filepath in files:
        try:
            tu = index.parse(
                str(filepath),
                args=["-std=c++17", "-I" + str(repo_path)],
            )
        except Exception:
            continue

        visitor = CppVisitor(str(repo_path), str(filepath), types, seen_fqns)
        visitor.visit(tu.cursor)

    types = [t for _, t in types]

    result = {
        "schemaVersion": "1.0",
        "projectName": repo_path.name,
        "repository": str(repo_path),
        "generatedAt": "",
        "sourceRoots": [str(repo_path)],
        "types": types,
        "diagnostics": [
            {
                "severity": "INFO",
                "code": "CPP_EXTRACTION",
                "message": "Extracted {} types from {} files".format(
                    len(types), len(files)
                ),
            }
        ],
    }
    return result


def _empty_result(repo_path, message):
    return {
        "schemaVersion": "1.0",
        "projectName": repo_path.name,
        "repository": str(repo_path),
        "generatedAt": "",
        "sourceRoots": [str(repo_path)],
        "types": [],
        "diagnostics": [
            {"severity": "INFO", "code": "CPP_EXTRACTION", "message": message}
        ],
    }


class CppVisitor:
    def __init__(self, repo_dir, filepath, types, seen_fqns):
        self.repo_dir = repo_dir
        self.filepath = filepath
        self.types = types
        self.seen_fqns = seen_fqns
        self._ns_stack = []

    @property
    def _namespace(self):
        return "::".join(self._ns_stack) if self._ns_stack else ""

    def _qualified_name(self, name):
        ns = self._namespace
        return ns + "::" + name if ns else name

    def visit(self, cursor, depth=0):
        if cursor.kind == CursorKind.NAMESPACE:
            self._visit_namespace(cursor)
        elif cursor.kind in (CursorKind.CLASS_DECL, CursorKind.STRUCT_DECL):
            if cursor.is_definition():
                self._visit_class(cursor)
        else:
            for child in cursor.get_children():
                self.visit(child, depth + 1)

    def _visit_namespace(self, cursor):
        name = cursor.spelling or "<anonymous>"
        self._ns_stack.append(name)
        for child in cursor.get_children():
            self.visit(child)
        self._ns_stack.pop()

    def _visit_class(self, cursor):
        name = cursor.spelling
        if not name:
            return
        qname = self._qualified_name(name)
        if qname in self.seen_fqns:
            return

        is_struct = cursor.kind == CursorKind.STRUCT_DECL
        is_abstract = self._has_pure_virtual(cursor)
        kind = "class"

        fields = []
        executables = []
        superclass = None
        interfaces = []

        for child in cursor.get_children():
            child_kind = child.kind

            if child_kind == CursorKind.CXX_BASE_SPECIFIER:
                base_name = self._base_name(child)
                if base_name:
                    if superclass is None:
                        superclass = base_name
                    else:
                        interfaces.append(base_name)

            elif child_kind in (CursorKind.FIELD_DECL, CursorKind.VAR_DECL):
                if child.access_specifier != AccessSpecifier.PRIVATE:
                    fields.append(self._map_field(child, is_struct))

            elif child_kind == CursorKind.CXX_METHOD:
                if not child.is_virtual_method():
                    pass
                ex = self._map_method(child, is_struct)
                executables.append(ex)

        qname_norm = qname.replace("::", ".")
        t = {
            "qualifiedName": qname_norm,
            "simpleName": name,
            "kind": kind,
            "superClass": superclass,
            "interfaces": interfaces,
            "fields": fields,
            "executables": executables,
            "abstractType": is_abstract,
            "finalType": False,
            "sourceFile": (
                str(Path(self.filepath).relative_to(self.repo_dir))
                if self.repo_dir
                else self.filepath
            ),
            "line": cursor.location.line if cursor.location else None,
        }
        self.seen_fqns[qname] = len(self.types)
        self.types.append((qname, t))

    def _has_pure_virtual(self, cursor):
        for child in cursor.get_children():
            if child.kind == CursorKind.CXX_METHOD and child.is_pure_virtual_method():
                return True
        return False

    def _base_name(self, cursor):
        ref = cursor.referenced
        if ref:
            return ref.spelling or cursor.displayname
        name = cursor.displayname or ""
        return name.split("::")[-1] if "::" in name else name

    def _map_field(self, cursor, is_struct):
        vis = self._visibility(cursor)
        if is_struct and vis == "private":
            vis = "public"
        return {
            "name": cursor.spelling,
            "type": cursor.type.spelling if cursor.type else "unknown",
            "visibility": vis,
            "staticField": False,
            "finalField": False,
            "line": cursor.location.line if cursor.location else None,
        }

    def _map_method(self, cursor, is_struct):
        name = cursor.spelling or ""
        if name.startswith("operator"):
            name = "operator"
        vis = self._visibility(cursor)
        if is_struct and vis == "private":
            vis = "public"
        return_type = cursor.result_type.spelling if cursor.result_type else "void"
        params = []
        for child in cursor.get_children():
            if child.kind == CursorKind.PARM_DECL:
                ptype = child.type.spelling if child.type else "unknown"
                params.append(
                    {"name": child.spelling or "", "type": ptype}
                )
        return {
            "name": name,
            "returnType": return_type,
            "visibility": vis,
            "constructor": cursor.is_default_constructor()
            or cursor.is_copy_constructor()
            or cursor.is_move_constructor(),
            "staticExecutable": cursor.is_static_method(),
            "abstractExecutable": cursor.is_pure_virtual_method(),
            "parameters": params,
            "line": cursor.location.line if cursor.location else None,
        }

    def _visibility(self, cursor):
        spec = cursor.access_specifier
        if spec == AccessSpecifier.PUBLIC:
            return "public"
        if spec == AccessSpecifier.PROTECTED:
            return "protected"
        if spec == AccessSpecifier.PRIVATE:
            return "private"
        return "public"


def main():
    if len(sys.argv) < 2:
        print(
            json.dumps({"error": "Usage: extract_cpp.py <repo_dir>"}),
            file=sys.stderr,
        )
        sys.exit(1)
    repo_dir = sys.argv[1]
    result = extract_from_repo(repo_dir)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
