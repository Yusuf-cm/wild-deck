# Seeded planetary surface: first geography layer

PlanetSurfaceGenerator samples the existing Earth-sized Planet. It generates elevation, land/ocean boundaries and three terrain-dependent resource-potential fields. There are no handcrafted continent outlines, biome labels, climate simulation, rivers, civilizations, discovery mechanics or resource extraction in this increment.

## Inspect it

    mvn test
    java -cp target/classes com.wilddeck.world.PlanetAtlasServer

Open http://127.0.0.1:8081. The read-only server binds to localhost. Enter any signed 64-bit seed, choose inspection detail, switch elevation/deposit layers, click a sample, or rotate the globe. The second command accepts an optional port argument.

For a self-contained review file:

    java -cp target/classes com.wilddeck.world.PlanetAtlasExporter target/planet-atlas

Open target/planet-atlas/wild-deck-planet-atlas.html. This portable version embeds Java-generated worlds for seeds 42, 1598 and -7. It needs no server, external assets or network. Arbitrary seeds and different inspection resolutions require the Java server; the portable preview states that limitation explicitly. JavaScript renders the data and does not implement a competing terrain generator.

## Generation

- Identity: signed 64-bit seed + generator version planet-surface-v1 + planet dimensions. Sea level is zero metres; geometry settings are constants for this version.
- Position is converted to a unit-sphere XYZ coordinate. Seeded 3D value noise, smooth interpolation, domain warping and several frequency bands create continental shapes and smaller details. A separate ridge field adds uplands.
- The algorithm is a stylized procedural height field, not plate tectonics, erosion, a climate model or a reconstruction of Earth. A particular number of continents or perfectly balanced starting positions is not guaranteed.
- Separate derived seed channels supply terrain, detail, ridges, geology and deposits. Sampling order and atlas resolution do not change a location. Future fields can be added without consuming a shared mutable random sequence.
- Iron favours elevated terrain; copper favours low/mid elevations; Stormglass is a rarer fictional mineral signal at higher elevations. Each also uses geology and its own smooth clustered field. Ocean samples contain no deposits in this first version.
- Potential is a 0–100 signal at a point, not a percentage of a finite inventory or mining yield. Stormglass has no implemented magical effect.
- Atlases use 180×90, 360×180 or 720×360 equirectangular samples. Land coverage is weighted by cosine of latitude to account for polar distortion. Pixels are previews, not authoritative regions or minimum world-detail units.
- The continuous sampler can evaluate other coordinates on demand. Longitude wraps continuously, and all longitudes at a pole produce the same sample. The browser's globe is a projection of the coarse atlas, not a separate world.
- Terrain colours show elevation and depth, not generated forests, ice biomes or ecosystems.

## Boundaries and compatibility

The viewer is an omniscient developer tool. It must not be used as a kingdom's knowledge view. Player discoveries, mutable world changes, material reactions and finite resource quantities belong to later layers.

Existing quick/full matches, their saves and hosting are unchanged. Generated geography is not silently attached to those match models. No deployment, billing, credentials or paid resources are needed for this tool.

Save metadata and preview JSON include the generator version and seed. The current viewer rejects unknown versions. A frozen reference-world fingerprint guards version-one output; future algorithm changes must make an explicit version/migration decision. This release does not claim to provide historical generator implementations or mutable-world saves.

## Verification

PlanetSurfaceGeneratorTest covers repeatability, request-order/detail independence, different/high-bit seeds, seams and poles, suitability, bounds, clustering, detached arrays, exact metadata and the version-one reference fingerprint.

PlanetAtlasServerTest checks real HTTP responses, sampled Java values, bounds, read-only behaviour and rejected parameters. The browser check exercises seed changes, repeatability, resource layers, inspection, globe rotation, exact large seeds, errors, export, mobile layout and offline rendering.
