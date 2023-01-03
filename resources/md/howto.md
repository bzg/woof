<div class="container">


# About Woof!

This website is an instance of [Woof!](https://sr.ht/~bzg/woof/), an application that tracks and
exposes useful interactions on mailing lists.

Woof! can typically be used for software development mailing lists
when these lists serve as channels for reporting bugs, submitting
patches, proposing feature requests, sharing tips, etc.


# Reports


## Report types

<table border="2" cellspacing="0" cellpadding="6" rules="groups" frame="hsides">


<colgroup>
<col  class="org-left" />

<col  class="org-left" />

<col  class="org-left" />
</colgroup>
<thead>
<tr>
<th scope="col" class="org-left">Report</th>
<th scope="col" class="org-left">Permission</th>
<th scope="col" class="org-left">Specificity</th>
</tr>
</thead>

<tbody>
<tr>
<td class="org-left"><code>bug</code></td>
<td class="org-left">Anyone</td>
<td class="org-left">Bug can have a version number as [BUG version]</td>
</tr>


<tr>
<td class="org-left"><code>patch</code></td>
<td class="org-left">Anyone</td>
<td class="org-left">Woof! stores and exposes the patch itself when possible</td>
</tr>


<tr>
<td class="org-left"><code>request</code></td>
<td class="org-left">Anyone</td>
<td class="org-left">Any request can be voted upon with +1 and -1</td>
</tr>


<tr>
<td class="org-left"><code>blog</code></td>
<td class="org-left">Anyone</td>
<td class="org-left">the full body of the email is indexed/searched</td>
</tr>


<tr>
<td class="org-left"><code>annoucement</code></td>
<td class="org-left">Maintainers</td>
<td class="org-left">the full body of the email is indexed/searched</td>
</tr>


<tr>
<td class="org-left"><code>release</code></td>
<td class="org-left">Maintainers</td>
<td class="org-left">a new release closes the related changes</td>
</tr>


<tr>
<td class="org-left"><code>change</code></td>
<td class="org-left">Maintainers</td>
<td class="org-left">to announce upcoming changes only</td>
</tr>
</tbody>
</table>


## Adding a report

Adding a report to Woof! is done by using subject prefixes:

-   `bug` : `[BUG]` or `[BUG x]` where x is a version number
-   `release` : `[RELEASE x]` or `[REL x]` where x is a version number
-   `change` : `[CHANGE x]` where x is a future (not released) version number
-   `patch` : `[PATCH]` or `[PATCH n/m]`
-   `request` : `[FR]` or one of `[FP, RFC, RFE, TASK, POLL]`
-   `blog` : `[BLOG]` or `[TIP]`
-   `annoucement` : `[ANNOUCEMENT]` or `[ANN]`


## Report states

All report types can be in a combination of these states: (un)acked,
(un)owned and (un)closed.

-   `Acked` means that someone took the next sensical action: e.g. for a
    bug, someone confirmed it; for a patch, someone reviewed it.
-   `Owned` means someone claimed to handle this report.  You can own a
    report that has not been acked.
-   `Closed` means the report has been closed, either because it has been
    fixed (for a bug), canceled or done in any fashion.


## STRT 


## Triggering updates

Some words at the beginning of a line in the body of a reply to a
report will trigger updates of this report.

E.g. if a line in your reply to a bug report starts with `Confirmed.`,
the bug report will be updated as "Confirmed" in Woof!.

Here are the default "triggers", the terms you can use for trigger an
update:

-   `bug` : `Confirmed`, `Handled`, `Fixed` or `Canceled`
-   `blog` : `Canceled`
-   `patch` : `Approved`, `Reviewed`, `Handled`, `Applied` or `Canceled`
-   `request` : `Approved`, `Handled`, `Done` or `Canceled`
-   `annoucement` : `Canceled`
-   `release` : `Canceled`
-   `change` : `Canceled`

**Note**: A punctuation mark among `;:,.` is *mandatory* for these reports and
action words (`Confirmed`, `Approved`, etc.) are all case-sensitive.


## Updating priority

You cannot set the priority of a report directly: it is computed based
on whether the report is important and urgent.

-   To set a report as important, use "Important" in a reply.
-   To set a report as unimportant, use "Unimportant" in a reply.
-   To set a report as urgent, use "Urgent" in a reply.
-   To set a report as not urgent, use "Not Urgent" in a reply.


## Using multiple triggers

You can use multiple triggers in the same email.  E.g. in a reply
against a bug report:

    Confirmed.
    Urgent.
    Important.

will mark the bug report as confirmed, and set it as important and
urgent, giving it the highest priority.


# Search

Woof! web interface allow users to search reports.

-   `agenda` will find reports which subject matches `agenda`
-   `from:user@woof.io` will list reports from user@woof.io
-   `acked:user@woof.io` will list reports *acked* by user@woof.io
-   `owned:user@woof.io` will list reports *owned* by user@woof.io
-   `closed:user@woof.io` will list reports *closed* by user@woof.io

You can use abbreviations (f[rom], a[cked], o[wned], c[losed]) and
combine search parameters:

-   `f:user1@woof.io a:user2@woof.io` will list possible reports *from*
    user1@woof.io and *acked* by user2@woof.io.


# Admins and maintainers

Each Woof! instance comes with a default admin.

**Admins** can update the main configuration:

-   `Global notifications: [true|false]` : Enable/disable mail notifications globally
-   `Maintenance: [true|false]` : Put the website in maintenance mode
-   `[Add|Remove] admin: woof@woof.io` : Add or remove an admin
-   `[Add|Remove] maintainer: woof@woof.io` : Add or remove a maintainer
-   `[Delete|Undelete]: woof@woof.io` : Clean up past reports
-   `[Ignore|Unignore]: woof@woof.io` : Ignore *future* reports

`Add`, `Remove` and `(Un)Delete/(Un)Ignore` commands can accept several
arguments: you can use `Ignore: user1@woof.io user2@woof.io` to ignore
future messages from these two users.

**Maintainers** can perform three actions:

-   `Add maintainer: woof@woof.io`
-   `Delete: woof@woof.io`
-   `Ignore: woof@woof.io`

Note that maintainers cannot remove admins or other maintainers and
they cannot undelete mails or unignore contributors.

</div>

