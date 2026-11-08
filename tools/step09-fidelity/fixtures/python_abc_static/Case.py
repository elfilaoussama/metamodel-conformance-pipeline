from abc import ABC, abstractmethod
class Base(ABC):
    @abstractmethod
    def action(self) -> int:
        return 1
    @staticmethod
    @abstractmethod
    def utility() -> int:
        return 2
class Concrete(Base):
    def action(self) -> int:
        return super().action()
    @staticmethod
    def utility() -> int:
        return Base.utility()
assert Concrete().action() == 1
assert Concrete.utility() == 2
