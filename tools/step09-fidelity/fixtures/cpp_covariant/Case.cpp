struct Animal { virtual ~Animal() = default; };
struct Dog : Animal {};
struct Factory { virtual Animal* make(); };
struct DogFactory : Factory { Dog* make() override; };
