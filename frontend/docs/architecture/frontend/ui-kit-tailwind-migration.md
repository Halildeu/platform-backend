# Tailwind UI Kit Migration Plan (Ant Design Replacement)

## 1. Scope

We will replace *all* Ant Design usage across Shell and every MFE with a Tailwind + custom component kit that is backed by our theme tokens. The migration covers:

1. Shell core layout (Top Bar, Sidebar, Page Header, Filter Bar, PageLayout, Modals/Drawers, Notification Center).
2. Shared UI Kit packages (FormDrawer, DetailDrawer, ReportFilterPanel, FilterBar, EntityGridTemplate wrappers).
3. App widgets in `apps/mfe-reporting`, `apps/mfe-users`, `apps/mfe-access`, etc.
4. Theme/ConfigProvider glue (Ant Design’s ConfigProvider and CSS imports will be removed).

## 2. Component Mapping

| Ant Design Component | Replacement Strategy |
| --- | --- |
| `Button`, `ConfigProvider` | New `ui-kit` Button component built with Tailwind + tokens. ConfigProvider removed; ThemeProvider only sets CSS vars. |
| `Input`, `Select`, `DatePicker`, `Switch` | Headless UI (or base `<input>`/`<select>`) wrapped with Tailwind classes and token-based states. |
| `Modal`, `Drawer`, `Popover`, `Tooltip` | New primitives using Radix UI (or headless patterns) plus Tailwind for styling. |
| `Layout`, `Card`, `Space`, `Grid` | Replace with Tailwind utility layouts + semantic components (`PageLayout`, `Card`, `Stack`). |
| `message`/`notification` | Custom toast/notification center built on existing Shell notify API. |
| `Tabs`, `Tag`, `Avatar`, `Badge`, `Breadcrumb`, `Pagination` | New UI Kit components. |

## 3. Migration Phases

### Phase 1 – Foundation
1. Build Tailwind-based primitives inside `packages/ui-kit` (Button, Input, Modal, Drawer, Toast, etc.). ✅ Button/Input + Modal/Drawer Tailwind varyantları hazırlandı.
2. Update ThemeProvider to drop Ant Design ConfigProvider; ensure CSS variables cover every semantic token.
3. Document API + Storybook stories for new primitives.

### Phase 2 – Shell/Core
1. Refactor `apps/mfe-shell/src/app/ShellApp.ui.tsx` to use Tailwind components (TopBar, Sidebar, PageLayout, Notification Center).
2. Replace AntD form controls in Shell-based components (Register/Login, Notification center).
3. Update ReportFilterPanel, FilterBar, PageLayout, FormDrawer, DetailDrawer (in `packages/ui-kit`) with new primitives.

### Phase 3 – Feature MFEs
1. Reporting MFE: replace Buttons, Inputs, Selects, Modals, Table wrappers (keeping AG Grid) with the new kit.
2. Users & Access MFEs: migrate forms, drawers, filter panels, tables.
3. Remove Ant Design imports from every `apps/*` and `packages/*` file.

### Phase 4 – Cleanup
1. Remove `antd` dependency from `package.json` (root + packages) and ensure lint rule forbids `antd` imports.
2. Update docs (`theme/tokens`, `ux`, `grid-template`, `mf-check`) to reflect Tailwind stack only.
3. Adjust Storybook stories to showcase Tailwind components + theme tokens exclusively.

## 4. Testing & Guardrails
- **Storybook**: add stories for every new UI Kit component and the Foundation/Theme Tokens page.
- **Playwright**: extend existing visual/a11y suite to cover the new kit (buttons, modals, drawers, filter bar).
- **Cypress**: ensure feature flows (Reporting, Users, Access) still pass guard tests after replacements.
- **CI**: add ESLint rule to block `antd` imports once migrations per file complete.

## 5. Risks
- Large diff per module – mitigate by migrating component families incrementally.
- Accessibility regressions – run Playwright axe tests after each stage.
- Layout shifts – verify with QA/Design per acceptance docs (`docs/architecture/frontend/ux`).

## 6. Next Steps
1. Kick off Phase 1: create Tailwind primitives in `packages/ui-kit` with tokens + Storybook coverage.
2. Plan a branch per component cluster (Buttons, Inputs, Modals, Layout) to refactor Shell and shared components.
3. After primitives are merged, begin Phase 2 starting with Shell layout refactor.
