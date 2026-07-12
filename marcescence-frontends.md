# Front-end integration (ES-DE)

Bannerlator can be launched from Android front-ends like **ES-DE**, so your exported Windows
shortcuts show up as games. It doesn't work out of the box — you have to point the front-end at
Bannerlator's launch activity.

> Thanks to **xabbu33** for the original pull request and tutorial this guide is based on.

## Package & activity

Bannerlator's installed package (applicationId) is **`com.winlator.banner`**, but its launch
activity class is still **`com.winlator.star.XServerDisplayActivity`** (the code namespace was kept
from the upstream base). Because the package and the namespace differ, you must use the
**fully-qualified** component — the `/.XServerDisplayActivity` shorthand will *not* work:

```
com.winlator.banner/com.winlator.star.XServerDisplayActivity
```

> **Installed a differently-named build?** The alternate flavors ship under a different package —
> swap the first half accordingly. The activity part is identical on every flavor.
>
> | Flavor | Package (applicationId) |
> |---|---|
> | Bannerlator Bionic (standard) | `com.winlator.banner` |
> | Bannerlator Bionic Ludashi | `com.ludashi.benchmark` |
> | Bannerlator Bionic PuBG | `com.tencent.ig` |

## The `am start` command

Use the block for the build you installed. `{file.path}` is the full path to an exported Bannerlator
`.desktop` shortcut.

**Bannerlator Bionic — standard (`com.winlator.banner`):**

```
am start \
  -n com.winlator.banner/com.winlator.star.XServerDisplayActivity \
  -e shortcut_path {file.path} \
  --activity-clear-task \
  --activity-clear-top
```

**Bannerlator Bionic Ludashi (`com.ludashi.benchmark`):**

```
am start \
  -n com.ludashi.benchmark/com.winlator.star.XServerDisplayActivity \
  -e shortcut_path {file.path} \
  --activity-clear-task \
  --activity-clear-top
```

**Bannerlator Bionic PuBG (`com.tencent.ig`):**

```
am start \
  -n com.tencent.ig/com.winlator.star.XServerDisplayActivity \
  -e shortcut_path {file.path} \
  --activity-clear-task \
  --activity-clear-top
```

## ES-DE setup

Add this to your `custom_systems`/`es_find_rules.xml`:

```xml
<emulator name="BANNERLATOR">
    <rule type="androidpackage">
        <entry>com.winlator.banner/com.winlator.star.XServerDisplayActivity</entry>
    </rule>
</emulator>
```

And in `es_systems.xml`:

```xml
<system>
    <name>windows</name>
    <fullname>Microsoft Windows</fullname>
    <path>%ROMPATH%/windows</path>
    <extension>.desktop .DESKTOP</extension>
    <command label="Bannerlator (Standalone)">%EMULATOR_BANNERLATOR% %ACTIVITY_CLEAR_TASK% %ACTIVITY_CLEAR_TOP% %EXTRA_shortcut_path%=%ROM%</command>
    <platform>windows</platform>
    <theme>windows</theme>
</system>
```

Drop your exported `.desktop` shortcuts from Bannerlator into `ROMs/windows/` and they'll show up as
games in ES-DE.
