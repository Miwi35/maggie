# Grocery Item Detail View — Shape

## Problem
Tapping a grocery item on mobile or clicking it on admin has limited interaction. Users want to see product details (name, quantity, unit, category, store, source) without editing.

## Solution
Read-only detail view that reuses the add-item layout:
- **Mobile**: Bottom sheet with disabled fields, pre-filled from the tapped item
- **Admin**: MUI Dialog with disabled fields, pre-filled from the clicked item

## Constraints
- No API changes — purely frontend
- Same layout as add-item form, but read-only
- Mobile: normal tap opens detail; long press still enters selection mode; swipe still works
- Admin: clicking item text opens detail; checkbox area still toggles checked state
