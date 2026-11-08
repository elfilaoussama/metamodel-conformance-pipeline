class Rebound:
    def method(self) -> int: return 1
    def method(self) -> int: return 2
assert Rebound().method() == 2
