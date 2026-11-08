interface Left { String key(); }
interface Right { String key(); }
interface Combined extends Left, Right {}
class Implementer implements Combined { @Override public String key() { return "yes"; } }
