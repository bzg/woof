Woof! monitors updates sent to a mailbox and exposes them on the web.
Typically, this mailbox is subscribed to a mailing list with a public
online archive so that Woof! reports can link to it.

Woof! tries to be **a good companion for free software maintainers** who
work with mailing lists by allowing them to focus on *bugs* and *patches*.
It also aims at **making life easier for users** by pointing at important
news such as upcoming changes.

Woof! does not change the way maintainers use a mailing list for the
development of their projects: with a minimalistic set of conventions,
Woof! will extract what's important for everyone.

![img](woof.png)

See [the howto](resources/md/howto.md) for further instructions on how to use it.


# Requisits

You need Clojure and Java on the machine to deply this application.

Run `~$ apt install clojure` or see [this guide](https://clojure.org/guides/getting_started).

Run `~$ apt install default-jre` or refer to your distribution.


# Configure

You need to set some environment variables to let the application run.

See <src/bzg/config.clj> for the list of environment variables and
other configuration variables.

Run this to check your configuration:

    ~$ clj -M:test


# With deps.edn

Run with:

    ~$ clj -M:run

Deploy with:

    ~$ clj -M:uberdeps
    ~$ java -cp target/woof.jar clojure.main -m bzg.web


# With leiningen

Run with:

    ~$ lein run

Depuis with:

    ~$ lein uberjar
    ~$ java -jar target/woof.jar


# Contribute

Feedback and contributions are welcome, you can e.g.:

-   Write a HTML theme
-   Add more tests
-   Report bugs

Woof! is alpha software.  One instance is used by the [Org-mode](https://updates.orgmode.org)
community.


# Support the Clojure(script) ecosystem

If you like Clojure(script), please consider supporting maintainers by
donating to [clojuriststogether.org](https://www.clojuriststogether.org).


# License

© Bastien Guerry 2020-2021

Woof is released under [the EPL 2.0 license](LICENSE).

