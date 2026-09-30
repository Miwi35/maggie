# Grocery Item Detail View — Plan

## Tasks

### 1. Mobile — ItemDetailSheet composable
- New file: `ItemDetailSheet.kt`
- Reuse AddItemSheet layout with disabled fields
- Title: "Details de l'article"
- No submit button, dismiss on back/tap outside

### 2. Mobile — Wire tap in GroceryListsScreen
- Add `detailItem` state
- In `combinedClickable.onClick`: open detail sheet when not selecting
- Render `ItemDetailSheet` when detailItem != null

### 3. Admin — Read-only detail dialog
- Add `detailItem` state in GroceryListView
- Dialog with same fields as add dialog, all disabled/read-only
- Title: "Details de l'article", single "Fermer" button

### 4. Admin — Split click handlers
- Checkbox `onChange` handles check toggle
- `ListItemButton onClick` opens detail view
- Drag handle remains unchanged
