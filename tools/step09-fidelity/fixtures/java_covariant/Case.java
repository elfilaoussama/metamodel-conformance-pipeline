class Animal {}
class Dog extends Animal {}
class Factory { Animal make() { return new Animal(); } }
class DogFactory extends Factory { @Override Dog make() { return new Dog(); } }
