# Apple Maps charging connector settings

**EV & routing → Apple Maps → Advanced** lets you change the plug types and rated AC/DC power OAL sends on the next CarPlay connection.

- **Automatic** uses plug types reported by the car. If it has no usable report, it uses **CCS1 + J1772** regardless of make, model, year, or region. It doesn't add plugs to a usable partial report.
- If no car-supplied ratings are available, the default is **150 kW for CCS1** and **11.5 kW for J1772**. These are generic defaults, not your car's measured limits. OAL only sends those ratings for their matching plugs.
- If the default doesn't match the car, select the actual plug types and edit the applicable ratings. **Reset connector settings** restores Automatic. Save changes; for USB CarPlay, unplug and reconnect to apply.

Rated connector power is separate from the **Average DC charging power** routing estimate. These settings don't change live battery or range data, turn EV sharing on, or require deleting the saved vehicle from Maps.
