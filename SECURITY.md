# Credentials and release packaging

This fork's Git tree and source archive contain no accessory identity and no Android release-signing secrets. CI checks (`scripts/check_public_tree.py`) reject credential containers and private-key blocks in tracked files. Tests generate synthetic identities at runtime. Source builds import no private assets; a CarPlay-capable build needs an explicit local directory and accepts only the two expected runtime files (see [Build](docs/BUILD.md)).

Anyone with an APK that contains an accessory identity can extract it. Local compilation, Git history removal and obfuscation do not make a bundled key confidential or revoke copies already shared. Publish only an APK whose identity you are allowed to distribute, and say in its release notes whether it contains one.

The Android APK-signing key is separate, stays local and is never bundled in the APK. Acceptance of an accessory identity by an iPhone does not establish Apple certification or guarantee future compatibility.

Review diagnostic reports before posting. Never include credentials, hotspot passwords or pairing records in public issues. Use GitHub private vulnerability reporting for sensitive findings.
