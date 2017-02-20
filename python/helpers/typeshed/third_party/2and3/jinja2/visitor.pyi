# Stubs for jinja2.visitor (Python 3.5)
#
# NOTE: This dynamically typed stub was automatically generated by stubgen.

class NodeVisitor:
    def get_visitor(self, node): ...
    def visit(self, node, *args, **kwargs): ...
    def generic_visit(self, node, *args, **kwargs): ...

class NodeTransformer(NodeVisitor):
    def generic_visit(self, node, *args, **kwargs): ...
    def visit_list(self, node, *args, **kwargs): ...
