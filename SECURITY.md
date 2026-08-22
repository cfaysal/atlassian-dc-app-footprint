# Security

## What these scripts do and do not do

Producing a report opens no outbound network connection, in either endpoint and in every
output format. Everything it produces is generated inside your own instance and returned
to the calling administrator. The analysis is read-only and creates, changes or deletes
no configuration.

Both endpoints additionally offer a page export. It writes exactly one Confluence page,
only when an administrator explicitly requests it, and only into the space they select.
A generated page carries a marker, and the export refuses to touch a page that does not
carry it, so it cannot overwrite a page it did not create. If the existing page cannot be
read or parsed, nothing is written at all.

The two endpoints reach Confluence differently, and the difference matters for you:

* The **Confluence endpoint** writes through the local Confluence API. No network
  connection is opened at any point.
* The **Jira endpoint** writes to a separate Confluence instance, so the call leaves
  Jira. It travels over an application link you have already configured and trust, using
  its existing authentication, as the administrator who runs the export. Nothing is sent
  anywhere else. Opening the report triggers no call at all: application links are read
  only after you open the export, and spaces and pages only after you pick a target.

Access is gated by ScriptRunner using the `groups` attribute on the endpoint:
`jira-administrators` for the Jira script, `confluence-administrators` for the Confluence
script. The gate is enforced by ScriptRunner before the script body runs.

## What the output contains

The report describes your instance: installed app keys and vendors, module names, custom
field names, screen and workflow names, space keys, and usage counts. Depending on your
naming conventions this can be commercially sensitive, and on some instances field or
space names carry customer or project names.

Treat a generated report as an internal document. Review it before sending it outside your
organisation.

## Permission model

The scripts read through the product APIs as the calling context permits. They do not
attempt to bypass permissions, and they do not elevate. Anything an administrator would be
unable to see is not made visible by running the report.

## Reporting a vulnerability

Please do not open a public issue for a security problem.

Use GitHub's private vulnerability reporting instead: open the **Security** tab of this
repository and choose **Report a vulnerability**. That channel is private between you and
the maintainer.

Include a description, the affected file, and the product and ScriptRunner versions
involved. You will get an acknowledgement, and either a fix or an explanation of why the
behaviour is intended.

## A note on installing scripts from the internet

You are about to paste a large Groovy file into an administrative console on a production
instance. Read it first, or have someone read it for you. That advice applies to this
repository exactly as much as to any other, and the files are deliberately commented so
that reading them is realistic.
