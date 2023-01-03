

# Extract useful mailing lists interactions from your mailbox

Woof! monitors a mailbox, capture useful email interactions and expose
them on a web page.  Imagine, for example, that your email address is
subscribed to numerous free software development lists and you want to
catch bugs reports, patches, feature requests sent to all these lists:
then you can plug Woof! and have your mailbox monitored, with the most
important information exposed on a web page.

![img](woof.png)

**CAVEAT: Woof! is still in alpha, things can move.**

See [this howto](resources/md/howto.md) for basic instructions on how to use Woof!

See [woof.bzg.fr](https://woof.bzg.fr/source/woof/) for a Woof! instance tracking the [Woof! mailing list](https://lists.sr.ht/~bzg/woof).

To follow what happens on this Woof! instance, you can also subscribe
to [this RSS feed](https://woof.bzg.fr/source/woof/index.rss).


# We don't want no issue tracker

Developing software by discussing on public mailing lists and sharing
patches by email works fine.

At some point, you may need to track bug reports, patches, etc.

If you are using [sourcehut.org](https://sourcehut.org/), perhaps you will set up a new tracker
for your project.  If your software is part of the GNU project, then
perhaps you'll set up debbugs for your package.  But these trackers
create *new communication channels*, new "databases" that you will have
to *maintain*&#x2014;and they are probably overkill for your needs.

**This is where Woof! comes in handy as a way to monitor mailing lists**.
You plug Woof! into your mailbox, it monitors emails sent to mailing
lists this mailbox is subscribed to and it extracts and exposes useful
information: bug reports, patches, changes, announcements, etc.

Woof! has been developed to help with [Emacs Org-mode](https://orgmode.org/) development where
there is a mailing list with a lot of useful interactions and the need
to promote upcoming changes, bug reports, patches to review, etc.

**Woof! is not a full-fledged project management tool**: e.g. it does not
allow someone to assign tasks to someone else, to close reports, etc.


# Design rationale

-   **Read only**: Woof! is not a database of issues you need to maintain.
    Useful information is extracted from upstream email interactions,
    emails are the sole source of truth.  So Woof! is read only: there
    is no login, no way to update stuff from the website.

-   **Decentralized**: Since Woof! is based on mailboxes and only reflects
    upstream intereactions, you can have several Woof! instances for the
    same mailing lists: each instance will reflect what is of interest
    for the person who deployed it.

-   **Minimalistic conventions**: Woof! tries to rely on minimalistic and
    realistic conventions for subject prefixes (e.g. [BUG]) and updates
    "triggers" (e.g. "Confirmed.").

-   **Configurable**: Woof! tries to be highly configurable.


# Features

-   Track various report types: bugs, patches, requests, etc.
-   Support tracking multiple lists
-   Expose reports as `rss`, `md`, `json` or `org`
-   Expose raw downloadable patches when possible
-   Support HTML and plain text emails
-   Track related reports and allow to list them
-   Track votes on requests (e.g. `[POLL]`)
-   Allow complex searches
-   Support theming
-   Support i18n


# Upcoming

There is no roadmap as I develop Woof! in my spare time, but here is a
list of ideas for future versions.

-   An Emacs tool to keep track of various Woof! instances
-   Implement notifications
-   Implement the overview page
-   Separate woof-server/monitor from woof-web
-   Enhance the hero header
-   Expose data through GraphQL
-   Add pagination
-   Add "events" report type
-   Allow individual notifications based on subject matches
-   Use integrant more consistently (to stop)
-   Add webhook


# Running Woof!


## Requirements

You will need a mailbox accessible via IMAP that Woof! will monitor.

This mailbox must receive mails sent to the mailing lists Woof! will
monitor and must also be able to send emails.

See the environment variables in <config_example.edn> for setting
the email information.

Woof! requires Clojure and Java.

You can install clojure with `~$ apt install clojure` or see [this guide](https://clojure.org/guides/getting_started).

You can install Java with `~$ apt install default-jre` or refer to your
distribution instructions.


## Configure

You need to copy `config_example.edn` as `config.edn` and to set
environment variables: see <config_example.edn> for the list.

`config_example.edn` also contains other configuration parameters that
you need to set.  You can also refer to <src/bzg/config.clj> which
contains other configuration defaults.


## Test

Once you are done configuring Woof!, you can check your configuration
with:

    ~$ clj -M:test


## Run/build/deploy with deps.edn

Run with:

    ~$ clj -M:run

Build and deploy with:

    ~$ clj -M:uberdeps
    ~$ java --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED -cp target/woof.jar clojure.main -m bzg.init


## Run/build/deploy with leiningen

Run with:

    ~$ lein run

Build and deploy with:

    ~$ lein uberjar
    ~$ java --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED -jar target/woof.jar


# Contributing

Contributions are welcome!  See .

Suggested contributions:

-   Write a new HTML theme
-   Support new languages
-   Enhance documentation
-   Enhance performance and accessibility
-   Add tests
-   Report bugs


# Support the Clojure(script) ecosystem

If you like Clojure(script), please consider supporting maintainers by
donating to [clojuriststogether.org](https://www.clojuriststogether.org).


# License

© Bastien Guerry 2020-2022

Woof is released under [the EPL 2.0 license](LICENSES/EPL-2.0.txt).

