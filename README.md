# DirtyTracer: Check Android ptrace tracing

This project shows a minimal way to check whether a process is under a persistent ptrace tracer.

## How it works

The probe runs in a **plain isolated process**: the service is declared with
`android:isolatedProcess="true"` and does not use `useAppZygote`.

A plain isolated process is forked by zygote. Android
[clears the supplementary groups](https://android.googlesource.com/platform/frameworks/base/+/master/core/jni/com_android_internal_os_Zygote.cpp)
only for child zygotes; a plain isolated process keeps zygote's supplementary groups, including the
[`readproc` group](https://android.googlesource.com/platform/system/core/+/master/rootdir/init.zygote64.rc)
(gid 3009). Under `hidepid`, that group is what makes the relevant `/proc` entries readable.

The service then executes:

```text
/apex/com.android.runtime/bin/crash_dump64 <pid> <pid> 0
```

`crash_dump64` first tries to `PTRACE_SEIZE` the target. When the target is already traced, the
kernel returns `EPERM`; `crash_dump64` reads `TracerPid` from `/proc/<pid>/status` and emits a
[fatal log line](https://android.googlesource.com/platform/system/core/+/master/debuggerd/crash_dump.cpp):

```text
failed to seize pseudothread: failed to attach to thread <pid>, already traced by <tracer-pid> (<tracer-name>)
```

The isolated service reads that line back through logd as the same uid:

```text
/system/bin/logcat -d -v epoch -s crash_dump64
```

and matches the exact message for the requested pid. The result is returned to the main process
over binder, so the app never needs to read another uid's logs directly.

## Targets

- **init (pid 1)** — the primary target. A persistent tracer on init is reported as
  `TRACED by <pid> (<name>)`. The result is positive-only: without the exact line it is
  `INDETERMINATE`, not "clean".
- **zygote (the main process's parent)** — a diagnostic target. Zygote is normally traced only
  while a tracer injects into it, so at steady state it is expected to be `NOT TRACED`.

## Output

The app shows the device fingerprint and kernel release, followed by a short summary:

```text
init (pid 1): TRACED by 1234 (monitor)
zygote (pid 1263): NOT TRACED
verdict: DETECTED
```

## Requirements

- Android 11 or newer (`minSdk` 30); the probe uses `bindIsolatedService`, available since
  Android 10. Tested on Android 16.
- A plain isolated service. `useAppZygote="true"` produces a child zygote whose supplementary
  groups are cleared, so the probe cannot see the target.
- `/proc` mounted with `hidepid` protection and the `readproc` gid, as on AOSP.
- A short-lived check: if the tracer detaches while the probe runs, the result can change; the
  probe repeats the check to narrow that window.

## Build

```bash
./gradlew :app:assembleDebug
```

## License

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
