# How to Ignore Files in KtLint

To ignore specific files or directories (like generated code) in KtLint, you should use the `.editorconfig` file. This is the preferred method over using `.ktlintignore` or build configurations.

## Instructions

1.  Open the `.editorconfig` file in the root of your project.
2.  Add a section matching the glob pattern of the files you want to ignore.
3.  Set the `ktlint` property to `disabled`.

### Example: Ignoring Generated Code

```editorconfig
# Ignore generated code from KtLint checks
[**/generated/**/*.kt]
ktlint = disabled
```

### Example: Ignoring Specific Files

```editorconfig
# Ignore legacy file
[src/main/kotlin/com/example/Legacy.kt]
ktlint = disabled
```

## Why use .editorconfig?

*   **Standardization**: Uses the standard EditorConfig format.
*   **IDE Support**: Many IDEs respect `.editorconfig` settings automatically.
*   **Version Control**: Keeps configuration in a single, version-controlled file.
