**SFTP testing for issue #14.** Add an SFTP repository, generate its SSH key,
authorize the public key and verify the server fingerprint. Try a small backup,
a scheduled run and a restore. [SFTP setup and testing instructions](https://github.com/JayXT/ResticDroid/blob/v0.5.0-beta.1/README.md#testing-sftp-in-050-beta1).

Install the APK matching your device's CPU: `arm64-v8a` for almost every phone
made since 2016, `armeabi-v7a` for older 32-bit devices, `x86_64` for emulators.

Verify what you downloaded against `SHA256SUMS` before installing.
`BUILD-INFO.txt` records the exact restic commit and toolchain this build came
from.
