# AdvancedSoccer — Stadium Crowd Resource Pack

Minecraft resource pack with real stadium crowd sounds for the [AdvancedSoccer](https://github.com/ballgame/AdvancedSoccer) plugin.

## Download (raw URL for server config)

After pushing to GitHub, use:

```
https://raw.githubusercontent.com/Alexandr290/footballpack/main/ballgame-crowd-pack.zip
```

## Server config (`plugins/AdvancedSoccer/config.yml`)

```yaml
crowd:
  real-sounds: true
  resource-pack:
    mode: url
    url: "https://raw.githubusercontent.com/Alexandr290/footballpack/main/ballgame-crowd-pack.zip"
    use-hash: false
```

Then `/soccer reload`.

## Manual install (no download server)

Copy `ballgame-crowd-pack.zip` to `.minecraft/resourcepacks/` and enable it in **Options → Resource Packs**.

## Sounds

Royalty-free stadium recordings from [Mixkit](https://mixkit.co/free-sound-effects/crowd/). See `../src/main/resources/crowd-sounds/ATTRIBUTION.txt` in the main plugin repo.
