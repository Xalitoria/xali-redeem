# XaliRedeem

**XaliRedeem** is a voucher plugin for Paper and Folia, without the voucher items. A player holds vouchers in the database
(a shop, a vote reward or an event hands them out with a command) and redeems them from a menu or with a command. A voucher
has **conditions**, such as free inventory space or a permission, and redeeming it runs commands, gives items and tells
the player. A voucher can also be free to anyone who passes its conditions, with a cooldown and a limit, which makes a daily
reward or a one-time gift.

The menu is [XaliMenu](../menu/README.md)'s, and optional: with `menu.enabled: false`, or without XaliMenu, `/redeem` lists
the vouchers in chat, each one a click away from being redeemed, and everything else works the same.

## Contents

- [Installation](#installation): [Requirements](#requirements), [Modules](#modules),
  [Building and installing](#building-and-installing), [Removal](#removal)
- [How it works](#how-it-works)
- [Configuration](#configuration): [Files](#files), [config.yml](#configyml), [Vouchers](#vouchers), [The menu](#the-menu)
- [Usage](#usage): [Commands](#commands), [Placeholders](#placeholders),
  [Handing out vouchers from other plugins](#handing-out-vouchers-from-other-plugins)
- [Development](#development)
- [See also](#see-also)

## Installation

### Requirements

| Plugin | Needed | Purpose |
|---|---|---|
| XaliMenu | No | The voucher menu, turned on with `menu.enabled` (on by default). It needs PlaceholderAPI too, which it requires itself. |
| PlaceholderAPI | No | The `%xaliredeem_...%` placeholders, which the menu reads the vouchers' text from; `placeholder` conditions; placeholders in rewards. |

### Modules

| Module | Description |
|---|---|
| `redeem/paper` | `mc.xalitoria.redeem.paper`: the plugin, `xali-redeem-<version>-paper.jar`. |

### Building and installing

```console
user $ ./gradlew :redeem:paper:shadowJar
```

Copy `build/libs/redeem/xali-redeem-<version>-paper.jar` into `plugins`. The descriptor comes from the
[Gradle plugin](../xali-gradle/README.md); the `paper { }` block is in `redeem/build.gradle.kts`. The plugin loads after XaliMenu
and PlaceholderAPI when they are installed. The API of XaliMenu is not bundled: it comes from XaliMenu's jar when `menu.enabled`
is on, and nothing of it is loaded otherwise.

### Removal

Delete the jar. `plugins/XaliRedeem/`, the table `<prefix>holding` and `plugins/XaliMenu/menus/redeem.conf` stay. Delete
the menu file too, or XaliMenu reports a menu that names an action that is gone.

## How it works

1. A voucher is a file in `plugins/XaliRedeem/vouchers/<name>.yml`, see [Vouchers](#vouchers). The first start writes
   `example.yml` there.
2. `/redeem admin give <player> <voucher> [amount]` gives a player vouchers (from the console too, so a shop, a crate or
   a vote reward can run it). Free vouchers are not given; anyone can redeem them.
3. The player opens `/redeem`, or types `/redeem <voucher>`. Redeeming checks the cooldown, the limit and **every condition**,
   uses the voucher up, checks the conditions once more (so a second redeem started at the same moment cannot use the same
   free slots twice) and runs the rewards. If a condition fails the second time, or the player left meanwhile, the voucher is
   put back.
4. The count, the number of redeems and the time of the last one are kept in the database. Servers that share one database share
   the vouchers, and the last voucher goes to one of two servers that redeem at once.

## Configuration

### Files

| File | Holds |
|---|---|
| `plugins/XaliRedeem/config.yml` | Settings, see below. |
| `plugins/XaliRedeem/language/<language>.yml` | Every message, written with the defaults on the first start. |
| `plugins/XaliRedeem/vouchers/<name>.yml` | One voucher each. |
| `plugins/XaliRedeem/xaliredeem.db` | The data, with `database.type: sqlite`. |
| `plugins/XaliMenu/menus/redeem.conf` | The menu, written when it is missing (`menu.write-menu`). |

`/redeem admin reload` reads the configuration, the language and the vouchers again. A voucher that is wrong is left out and the
console says why; the others load. In a voucher file a misspelled key is an error, not ignored.

### config.yml

| Key | Default | Meaning |
|---|---|---|
| `debug` | `false` | Log what the plugin does. `/redeem admin debug` toggles it. |
| `language` | `en-us` | The file in `language/` that messages are read from. |
| `menu.enabled` | `true` | Use XaliMenu for the menu. Without XaliMenu the console says so once and the chat list is used. Turning it on while the server runs needs `/xmenu reload` to read the menu file. |
| `menu.name` | `redeem` | The name of the menu in XaliMenu. |
| `menu.write-menu` | `true` | Write that menu file when it is missing, so it can be edited there. |
| `per-page` | `8` | Lines per page of `/redeem list`. |
| `database.*` | SQLite | `sqlite`, `mariadb` or `mysql`, and the table prefix (`xaliredeem_`). |

### Vouchers

**File** `plugins/XaliRedeem/vouchers/daily.yml`: redeemed with `/redeem daily`.

```yaml
name: "&aDaily Gift"
description: ["&7Once a day."]
material: gold_ingot          # what the menu draws; any XaliMenu material (nexo:<id>, head:<player>...)
free: true                    # nobody holds it; anyone who passes the conditions can redeem it
show: HELD                    # ALWAYS, HELD (those who hold one; everyone, for a free voucher) or READY (only when redeemable)
cooldown: 1d                  # 30s, 5m, 2h, 1d or 1h 30m
max-redemptions: 0            # per player, ever; 0 for no limit
conditions:
  - type: inventory-space
    slots: 2
  - type: permission
    node: myserver.member
    message: "&cMembers only."
rewards:
  commands: ["give {player} diamond 3"]
  items:
    - material: apple
      amount: 3
      name: "&cShiny Apple"
  message: ["&aEnjoy, {player}!"]
```

A held voucher (`free: false`, the default) is used up by redeeming and given with `/redeem admin give`.

**Conditions** are tested when the player redeems and every time the menu is drawn, so the menu shows what stands in the way
(the first condition that fails, or the cooldown, the limit, or not holding the voucher). Each may have a `message` that
replaces the default text.

| Type | Settings | Holds when |
|---|---|---|
| `inventory-space` | `slots` (1) | The player has that many empty slots (armor and the off hand don't count). |
| `permission` | `node` | The player has the permission. |
| `permission-any` | `nodes` | The player has at least one of them. |
| `no-permission` | `node` | The player does not have it. |
| `world` | `worlds`, `invert` | The player is in one of the worlds, or in none of them with `invert: true`. |
| `playtime` | `min` | The player has played that long on this server (the game's own statistic). |
| `placeholder` | `value`, `operator`, `expected` | The placeholder compares as asked: `==`, `!=`, `>`, `>=`, `<`, `<=` or `contains`. Numbers compare as numbers, other text without regard to case. Needs PlaceholderAPI, and is read on every menu refresh, so use a quick one. |

**Rewards** run in this order. `{player}`, `{uuid}`, `{voucher}` (its name here) and `{name}` (its display name) are filled in, and
PlaceholderAPI's placeholders if it is installed.

| Key | Does |
|---|---|
| `commands` | Console commands, without the slash. |
| `player-commands` | Commands the player runs as themselves. |
| `items` | `material`, `amount`, `name`, `lore`. Put into the inventory; what does not fit is dropped at the player's feet, so give a voucher an `inventory-space` condition too. |
| `message` | Lines told to the player. Colors, hex, CMI gradients and MiniMessage work. |
| `broadcast` | Lines told to everyone. |

A voucher's file name is its name: lower case letters, digits, `_` and `-`. `list`, `menu`, `help`, `admin`, `give`, `take` and
`reload` are taken by the commands.

### The menu

`plugins/XaliMenu/menus/redeem.conf` is a plain XaliMenu menu with a paged layout. XaliRedeem adds two things to XaliMenu, so any menu
can use them:

| What | Name | Does |
|---|---|---|
| Catalog | `xaliredeem:vouchers` | The vouchers the player can see, one element each, with `{id}`, `{name}`, `{material}`, `{amount}`, `{redeemed}` and `{ready}`. |
| Action | `xaliredeem:redeem`, `voucher = "{id}"` | Redeems that voucher and draws the menu again. |

XaliMenu shows an element's values as plain text, **without colors**, so the item template reads the vouchers' formatted text from
placeholders instead: `%xaliredeem_name_{id}%`, `%xaliredeem_description_{id}_1%` and `%xaliredeem_status_{id}%`. The menu does
not need `commands` (`register = false`): `/redeem` opens it.

## Usage

### Commands

`/xaliredeem`, also `/redeem`, `/xredeem`, `/vouchers`, `/xvouchers`, `/voucher` and `/xvoucher`.

| Command | Permission | Does |
|---|---|---|
| `/redeem` | `xaliredeem.use` | Opens the menu, or lists the vouchers when there is no menu. |
| `/redeem <voucher>` | `xaliredeem.use` | Redeems a voucher. |
| `/redeem list [page]` | `xaliredeem.use` | The player's vouchers in chat; click one to redeem it. |
| `/redeem menu` | `xaliredeem.use` | Opens the menu; says so when it is not on. |
| `/redeem admin give <player> <voucher> [amount]` | `xaliredeem.admin` | Gives vouchers; the player is told. A player who has never been on this server can be named by UUID. |
| `/redeem admin take <player> <voucher> [amount]` | `xaliredeem.admin` | Takes up to that many. |
| `/redeem admin balance <player>` | `xaliredeem.admin` | What the player holds, how often they redeemed each voucher and when last. |
| `/redeem admin reset <player> <voucher>` | `xaliredeem.admin` | Starts the cooldown and limit of that voucher again. |
| `/redeem admin open <player>` | `xaliredeem.admin` | Opens the menu for a player (a console can use it from an NPC or a crate). |
| `/redeem admin reload`, `debug` | `xaliredeem.admin` | Reload the files; toggle the debug log. |

Everyone has `xaliredeem.use` unless a permission plugin takes it away; operators have `xaliredeem.admin`. Who may redeem a
particular voucher is up to its conditions.

### Placeholders

PlaceholderAPI answers from memory (what is known of players who are online), never from the database.

| Placeholder | Is |
|---|---|
| `%xaliredeem_total%` | How many vouchers of every kind the player holds. |
| `%xaliredeem_count%` | How many vouchers the player can see. |
| `%xaliredeem_amount_<id>%`, `%xaliredeem_redeemed_<id>%` | How many of it they hold, and how often they redeemed it. |
| `%xaliredeem_ready_<id>%` | `true` when they can redeem it now. |
| `%xaliredeem_status_<id>%` | What stands in the way, or "Click to redeem" (colored). |
| `%xaliredeem_cooldown_<id>%` | Seconds before they can redeem it again; 0 when they can. |
| `%xaliredeem_name_<id>%`, `%xaliredeem_description_<id>_<line>%` | The voucher's text, with its colors; the line counts from 1. |

### Handing out vouchers from other plugins

There is no API to compile against: run `/xaliredeem admin give {player} <voucher> [amount]` as the console, from a vote reward
([XaliVote](../vote/README.md)), a shop, a crate, a referral completion ([XaliRefer](../refer/README.md)) or a menu action.
For example, in XaliVote's `rewards.yml`:

**File** `plugins/XaliVote/rewards.yml`

```yaml
basic-vote:
  commands:
    - "xaliredeem admin give {player} vote-crate 1"
```

## Development

### Source layout

Everything is in `redeem/paper` (`mc.xalitoria.redeem.paper`), so there is no `common` or `velocity`:

| Package | Holds |
|---|---|
| `XaliRedeem.kt` | The composition root: wires everything in `onEnable`. |
| `voucher` | `Voucher` (the file's model), `Vouchers` (reading the folder), `Condition` (a sealed interface, read with kaml's `PolymorphismStyle.Property` and the key `type`) and `Status` (why a voucher cannot be redeemed). |
| `service` | `RedeemService` (check, use up, check again, run, give back) and `RewardRunner` (commands, items, messages). |
| `data/db` | `HoldingTable` (player, voucher, amount, redeemed, last) and `HoldingRepository`. Redeeming is one transaction, so two servers cannot spend the last voucher twice. |
| `data/config`, `data/model` | `MainConfig`, `MessageConfig` and the model classes. |
| `command`, `listener` | `RedeemCommand` (Brigadier) and `PlayerListener`. |
| `external` | `MenuHook` (the only class that touches XaliMenu's API), `RedeemExpansion` and `Placeholders` (PlaceholderAPI). |
| `resources` | `vouchers/example.yml` and `menu/redeem.conf`, the files written on the first start. |

### Testing

Run Gradle from the workspace root:

```console
user $ ./gradlew :redeem:paper:test
```

The tests read the voucher files (including the example), the conditions' comparisons, the messages and the database rules against
a real SQLite file: holding, giving, taking, cooldowns, limits, giving back, and several redeems racing for the last voucher.
What they cannot show (the plugin on a Folia server with XaliMenu, a player clicking the menu) is checked with the live harness of
`menu/live`: set it up with `menu/live/setup.sh`, copy the jar of this plugin into `$XM_DIR/server/plugins` and join with a bot.

| Test | Covers |
|---|---|
| `VoucherFiles` | Reading voucher files, the bundled example and misspelled keys. |
| `HoldingStore` | The database rules: holding, giving, taking, cooldowns, limits, giving back, and redeems racing for the last voucher. |
| `ConfigFile` | The defaults and wrong values of `config.yml`. |

**Adding a condition**

1. Add a `@Serializable` class with a `@SerialName` (the `type` of the file) implementing `Condition` in `voucher/Condition.kt`:
   `test(player)` runs on the player's thread and must be cheap, and `validate()` reports a wrong setting.
2. Add its default text to `MessageConfig` (the `when` that picks the message for a failed condition) and to the language file.
3. Add a case to `VoucherFiles`, and a row to the table under [Vouchers](#vouchers).

**Adding a reward**

1. Add the key to `Rewards` in `voucher/Voucher.kt` and run it in `RewardRunner`, in the order the table lists.
2. Fill in the placeholders the other rewards get, and test it through `VoucherFiles`.

> [!NOTE]
> XaliMenu strips colors from the values of a catalog. Formatted text (the name, the description, the status) reaches the
> menu through `%xaliredeem_...%` placeholders, not through `{name}`.

## See also

- [XaliMenu](../menu/README.md) — the menus, catalogs and actions XaliRedeem registers
- [XaliVote](../vote/README.md) and [XaliRefer](../refer/README.md) — plugins that can hand out vouchers
- [Gradle plugin](../xali-gradle/README.md) — descriptors and library loading
- [Root README](../README.md) — the workspace

## External resources

- [PlaceholderAPI](https://wiki.placeholderapi.com/) — the placeholders
