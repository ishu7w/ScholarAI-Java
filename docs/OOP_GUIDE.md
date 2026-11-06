# OOP concepts in this project

Read the Java classes in this order: `WebController` → `Scholarships` → `Matching` → `Store`.

1. **Encapsulation**: `Accounts`, `Documents`, and `Store` hold private dependencies and expose operations. A controller asks `accounts.register(...)`; it does not select a hashing algorithm or build reset tokens itself. `Documents` owns the private storage root, preventing filenames from selecting filesystem paths.
2. **Composition**: `Scholarships` contains a `Store` and a `Matching` engine. They are supplied through its constructor. This expresses “has a,” keeps responsibilities separate and lets tests supply an isolated database.
3. **Abstraction**: `Store` hides common persistence/JSON details behind methods such as `profile`, `scholarship`, `audit` and `notify`. Services state their workflows without repeating mapping logic. JDBC stays intentionally simple; services can also call `jdbc()` for explicit bound SQL, so this is a small helper boundary rather than a complete database abstraction. There is no ORM entity hierarchy.
4. **Polymorphism through an interface**: `Bootstrap implements CommandLineRunner`. Spring invokes the interface contract; the overridden `run` method seeds this application's data.
5. **Inheritance and method overriding**: `SecurityConfig` specializes Spring's `OncePerRequestFilter` and overrides `doFilterInternal`. Spring handles the surrounding filter lifecycle, while this implementation adds per-request account revocation and throttling.
6. **Records as result values**: `DocumentChecks.Result` groups status, flags and extracted data for one check. A record's fields are final and its constructor/accessors are supplied by Java. This is shallow immutability: final list/map references do not by themselves make their contents immutable. It describes a result without adding an entity/service hierarchy.

An interface or superclass for every service would add files without solving a current problem. This project uses ordinary classes and constructor injection where that is enough. JSON maps preserve the original flexible domain fields; SQL columns enforce identities, ownership and relationships.
