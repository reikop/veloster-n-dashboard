# App update feed preparation

The public RSA authorization key is `update-public.pem`; the matching key is embedded in `UpdateTrust.java`. No private signing key belongs in this repository.

No stable update manifests are published yet. Future signed metadata is read from `updates/stable/dashboard/` or `updates/stable/hub/` on `main`. APK bytes are downloaded from this repository's GitHub release assets, not from this directory.

Use [the preparation tool and format guide](../docs/APP_UPDATES.md) to produce authenticated online/offline bundles. Only a newer version of an already installed project app on the exact supported JSN/API17 build is eligible. Root/system firmware packages are outside this format.

Local preview bundles now exist for My Apps v11 and N Dashboard v7; they are not a published stable feed. The Hub-only bootstrap verifier is offline by default. The v12 used for emulator self-update tests is a private fixture and must never enter this feed.
