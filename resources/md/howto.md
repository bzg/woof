<div class="container">


# What is Woof! and how to use it?

Woof! monitors updates sent to a mailbox and exposes them on the web.

Typically, this mailbox is subscribed to a mailing list with a public
archive, so that Woof! can expose links to this archive.

Woof! tries to be **a good companion for free software maintainers** who
work with mailing lists by allowing them to focus on *confirmed bugs*
and *approved patches*.

It also tries to **make life easier for users** by pointing to important
news such as upcoming changes.

Woof! tries not to impose new constraints: just continue to work on
your mailing list and with a minimalistic set of conventions, Woof!
will extract what's important for users and maintainers.


# Triggering a report

Woof! watches for triggers at the beginning of the subject line:

-   `[ANN]` : An annoucement
-   `[BUG]` : A bug report
-   `[HELP]` : A help request

It also watches for patches:

-   `[PATCH]` : A single patch
-   `[PATCH n/m]` : A patch in a series
-   A multipart mail with a `text/x-diff` or `text/x-patch` MIME part

Some triggers are special:

-   `[CHANGE x]` : Announce a change in the release `x`
-   `[RELEASE x]` : Announce the release `x`

The `x` part is mandatory for changes and releases and it should not
contain any whitespace.

Announcing a release `x` moves changes for `x` from the *Upcoming changes*
section to the *Latest released changes* one.  Canceling a release moves
the changes back to the *Upcoming changes* section.


# Updating a report

After a bug, patch, announcement, change, release or help request has
been monitored, replies to the original mail can trigger actions.

Actions are declared at the beginning of a line in the reply.

-   For **bugs**:
    -   `Confirmed.` : Confirm a bug.
    -   `Fix.` : Mark a bug as fixed.

-   For **patches**:
    -   `Approved.` : Approve a patch.
    -   `Applied.` : Mark a patch as applied.

-   For **help requests**:
    -   `Handled.` : Mark a request as currently handled.
    -   `Done.` : Mark a task of helping as done.

For bugs, patches, requests, announcements, changes and releases, you
can also cancel them:

-   `Canceled`. : Mark the bug, patch, request, announcement, change or
    release as canceled.

**Note**: A punctuation mark among `;:,.` is *mandatory* for these reports.


# Notifications

Users receive a mail notification when they triggers a Woof! report.

You can turn control notifications by writing to the Woof! mailbox
with this command at the beginning of a line:

-   `Notifications: false` : To turn notifications off
-   `Notifications: true` : To turn notifications on


# Admins and maintainers

Each Woof! instance comes with a default admin.

**Admins** can perform these actions:

-   `[Add|Remove] admin: woof@woof.io`
-   `[Add|Remove] maintainer: woof@woof.io`
-   `[Ban|Unban]: woof@woof.io`
-   `[Ignore|Unignore]: woof@woof.io`

Admins can also update the configuration:

-   `Maintenance: [true|false]` : Put the website in maintenance mode
-   `Notifications: [true|false]` : Enable/disable mail notifications
-   `[Enable|Disable]: [feature]` : Enable or disable a feature

`[...|...]` stands for either `...` or `...` and `feature` can be one of `bug`,
`announcement`, `request`, `change`, `release` or `mail`.

**Maintainers** can perform these actions:

-   `Add maintainer: woof@woof.io`
-   `Ban: woof@woof.io`
-   `Unban: woof@woof.io`
-   `Ignore: woof@woof.io`

They cannot remove admins or maintainers and they cannot unignore and
unban other contributors.

</div>

