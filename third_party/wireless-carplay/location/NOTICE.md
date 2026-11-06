# Location compatibility references

The location implementation is GPL-3.0-only. `VehicleSpeedNmea` is an independent
Java implementation of the PASCD wire profile. Its two reference vectors and
format comparison come from LuKa's GPLv3 `mhi2-carplay-cluster` implementation,
pinned at commit `58fb9582b60255a7921e8fc63baddc7c83112965`:

- https://github.com/maxim-smirnov/mhi2-carplay-cluster/blob/58fb9582b60255a7921e8fc63baddc7c83112965/hook/vehicle/vehicle_hook.c
- https://github.com/maxim-smirnov/mhi2-carplay-cluster/blob/58fb9582b60255a7921e8fc63baddc7c83112965/tests/vehicle_hook_test.c

No MFi certification or access to Apple's current member specification is claimed.
Encoding tests use explicit fixtures. Production data must come from fresh,
dedicated wheel sensors with known slip information. GNSS speed, cached getters,
unknown slip and unobserved transmission state are not silently replaced with
made-up measurements. Transmission has an explicit unknown (`U`) representation.

Android measured-fix fallback emits only RMC with measured speed/course. Raw GGA
must supply its own mean-sea-level altitude; ellipsoid height is not substituted.
