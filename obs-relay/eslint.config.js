import js from '@eslint/js';
import globals from 'globals';
export default [{
  languageOptions: { globals: { ...globals.node, ...globals.commonjs } },
  rules: {
    ...js.configs.recommended.rules,
    'no-console': 'off',
    'no-unused-vars': ['warn', {
      argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none',
    }],
    'no-empty': ['warn', { allowEmptyCatch: true }],
  },
}];
