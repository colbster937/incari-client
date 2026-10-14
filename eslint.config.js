// ESLint for Incari's TypeScript. Warnings fail the build (--max-warnings 0), `any` is an error,
// and every eslint-disable comment has to say why.
import js from "@eslint/js";
import comments from "@eslint-community/eslint-plugin-eslint-comments/configs";
import tseslint from "typescript-eslint";

export default tseslint.config(
  // run/ holds local game and server directories (test servers, their plugins): never Incari code.
  // tools/onlinedev/peer.mjs is a development script for the online service, never shipped.
  { ignores: ["work/", "run/", "dist/", "**/build/", "node_modules/", "eslint.config.js", "tools/onlinedev/"] },
  js.configs.recommended,
  ...tseslint.configs.strictTypeChecked,
  ...tseslint.configs.stylisticTypeChecked,
  comments.recommended,
  {
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    linterOptions: {
      reportUnusedDisableDirectives: "error",
    },
    rules: {
      "@typescript-eslint/no-explicit-any": "error",
      "@eslint-community/eslint-comments/require-description": "error",
      // node:test's describe/it return promises the runner awaits itself.
      "@typescript-eslint/no-floating-promises": [
        "error",
        { allowForKnownSafeCalls: [{ from: "package", package: "node:test", name: ["describe", "it", "test"] }] },
      ],
    },
  },
);
