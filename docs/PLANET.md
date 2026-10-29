# Empty planet foundation

The current world-building step is an empty Earth-sized sphere, defined by Planet.emptyEarthSized(). Its working name is Wild Deck.

| Dimension | Value |
| --- | --- |
| Mean radius | 6,371 km |
| Diameter | 12,742 km |
| Circumference | approximately 40,030 km |
| Surface area | approximately 510 million square kilometres |
| Shape | ideal sphere |
| Coordinates | latitude/longitude in degrees |
| Latitude | 90 degrees south to 90 degrees north |
| Longitude | 180 degrees west to 180 degrees east, wrapping at the seam |
| Spatial units | kilometres |

Coordinates address positions continuously; no fixed grid, provinces, tiles or terrain cells are allocated. Great-circle distance gives the shortest surface separation. Geocentric XYZ coordinates provide a consistent centre and axes for future spatial use.

The planet's surface is undefined. There are no landmasses, oceans, elevations, climate, resources, settlements, life, cards or interactions on this planet yet. This is a spatial definition, not a generated Earth landscape or a graphical planet renderer. Its ideal spherical geometry is not a model of Earth's ellipsoid or physical processes.

The earlier three-site WorldSimulation experiment remains separate and is not attached to the planet. Existing matches are not changed. The immediate scope is only the empty planet; additions to its surface come in a later step.

Update: the next step is now available as the separate [seeded surface generator and atlas](PLANET_ATLAS.md). Planet itself still defines geometry only; PlanetSurfaceGenerator supplies the optional geography/deposit layer.

Verified on 2026-10-29: [code CI](https://github.com/Yusuf-cm/wild-deck/actions/runs/40841583900) passed six planet tests, 190 total Java tests and six browser-script checks. Planet checks cover dimensions, wrapping at the longitude seam, poles, antipodal distance, geocentric axes, invalid coordinates and distance symmetry/scaling.
