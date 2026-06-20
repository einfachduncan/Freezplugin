# ScreenShareFreeze

Paper/Spigot Plugin fuer Staff-Screenshares.

## Commands

- `/ss <spieler>`: teleportiert je nach Config und friert den Spieler ein.
- `/ss reload`: laedt die Config neu.
- `/dss <spieler>`: beendet den Freeze.

## Rechte

- `screenshare.use`: darf `/ss` und `/dss` nutzen.
- `screenshare.bypass`: kann nicht eingefroren werden.

## Config

`teleport-mode`:

- `STAFF_TO_TARGET`: Staff wird zum Spieler teleportiert.
- `TARGET_TO_STAFF`: Spieler wird zum Staff teleportiert.
- `FIXED_LOCATION`: Staff und Spieler werden zur Config-Location teleportiert.

Feste Screenshare-Location:

```yaml
screenshare-location:
  enabled: true
  world: world
  x: 100.5
  y: 80.0
  z: -50.5
  yaw: 0.0
  pitch: 0.0
```

Wenn `enabled: true` ist, werden beide Spieler dorthin teleportiert. Der eingefrorene Spieler steht automatisch 1 Block neben dem Staff.

Die fertige JAR kommt nach dem Build in `build/libs/ScreenShareFreeze-1.0.0.jar`.
