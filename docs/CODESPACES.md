# Wild Deck on GitHub Codespaces

GitHub Codespaces gives this repository a cloud development computer with VS Code, terminal access, Java 17, Maven, Git and GitHub CLI.

## Start the cloud computer

1. Open the Wild Deck repository on GitHub.
2. Click **Code**.
3. Open the **Codespaces** tab.
4. Click **Create codespace on main**.

GitHub will create the machine from `.devcontainer/devcontainer.json`.

## Verify the engine

In the Codespaces terminal run:

```bash
java -version
mvn -version
mvn test
```

## Start the playable alpha

```bash
mvn -q exec:java
```

Use a deterministic seed when reproducing a bug:

```bash
mvn -q exec:java -Dexec.args="42"
```

## Enable Groq

Never commit your API key.

Inside the Codespace terminal:

```bash
export GROQ_API_KEY="your-key-here"
mvn -q exec:java
```

For a persistent Codespaces secret, add `GROQ_API_KEY` in your GitHub Codespaces secrets and allow it for the `wild-deck` repository. The secret will then be available to the Codespace without being stored in the repository.

## Development rule

Use the Codespace as the real execution environment for playtesting:

1. reproduce with a seed,
2. run the game,
3. capture the engine failure or bad behavior,
4. write/update a test,
5. fix the responsible layer,
6. run `mvn test`,
7. replay the same seed.

Do not patch gameplay with one-off card-name logic when a general property/capability/state rule is appropriate.
