# Security Policy

## Reporting a vulnerability

**Do not open a public issue.** Use GitHub's private reporting, which is enabled
for this repository:

<https://github.com/padreset/e-padreset/security/advisories/new>

or the **Security** tab → **Report a vulnerability**. The report stays private
between you and the maintainer, which is what allows a fix to exist before the
problem is described in public.

Please include:

- what the app does, and what it does instead;
- the steps that produce it, and the printer model if one is involved;
- the version from **About**, and the APK's SHA-256 if you installed a release;
- whether it needs physical access, a rooted device, or a crafted model file.

**What to expect.** This is a single-maintainer project with no funding. Reports
are read, but there is no bounty and no guaranteed response time. A fix will be
credited in the advisory unless you ask otherwise.

## What is worth reporting

This app writes to a printer's service program over USB. The failures that matter
are the ones that make it write something other than what it said it would.

In scope:

- a write that lands at the wrong address, or that proceeds after verification
  has failed;
- a path where the backup is not taken before a write, or where a restore writes
  something different from what was backed up;
- anything that makes the bundled model data (`devices.xml`,
  `maintenance_boxes.xml`, `reinkpy_models.xml`) parse into a plan that differs
  from what the file says;
- disclosure of backup contents, which are stored in private preferences;
- code execution, or a crash reachable from a file the app reads.

Out of scope:

- that the app resets a waste-ink counter without the pad being replaced. That is
  what the tool is for, and the README says so as a warning rather than hiding it;
- anything requiring physical access to an unlocked device, or a rooted device;
- Epson's firmware behaviour, unless it lets this app write something harmful;
- defects in the projects this one derives from — those belong upstream, and
  `NOTICE` names them.

## What this app does not have

Worth knowing before looking for a class of bug that cannot exist here:

- **No permissions.** The source manifest declares none — no network, no storage,
  no camera.
- **Nothing is transmitted.** There is no `INTERNET` permission; links open in the
  user's browser.
- **No account, no telemetry, no analytics.**
- **Distribution is by sideloading**, and releases carry a build-provenance
  attestation. See the README's *Releasing* section for how to check one.

## Supported versions

Only the latest release. There are no maintenance branches.

| Version | Supported |
| ------- | --------- |
| 1.x     | Yes       |
| 0.1.x   | No — upstream's line, not this project's |
