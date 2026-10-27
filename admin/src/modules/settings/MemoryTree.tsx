import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import FolderOutlinedIcon from '@mui/icons-material/FolderOutlined'
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined'
import type { MemoryTreeEntry } from './memoryApi'
import { buildTree } from './memoryPaths'
import type { TreeNode } from './memoryPaths'

interface Props {
  files: MemoryTreeEntry[]
  selected: string | null
  expanded: Set<string>
  onToggle: (folder: string) => void
  onSelect: (path: string) => void
}

function Rows({ node, depth, ...props }: Props & { node: TreeNode; depth: number }) {
  return (
    <>
      {node.children.map((child) => {
        const indent = { pl: 1 + depth * 2 }
        if (!child.entry) {
          const open = props.expanded.has(child.path)
          return (
            <Box key={`d:${child.path}`}>
              <ListItemButton dense sx={indent} onClick={() => props.onToggle(child.path)} aria-expanded={open}>
                <FolderOutlinedIcon fontSize="small" sx={{ mr: 1 }} />
                <ListItemText primary={child.name} />
                {open ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" />}
              </ListItemButton>
              {open && <Rows node={child} depth={depth + 1} {...props} />}
            </Box>
          )
        }
        const entry = child.entry
        return (
          <ListItemButton
            key={`f:${child.path}`}
            dense
            sx={indent}
            selected={props.selected === child.path}
            onClick={() => props.onSelect(child.path)}
          >
            <DescriptionOutlinedIcon fontSize="small" sx={{ mr: 1 }} />
            <ListItemText
              primary={entry.title}
              secondary={entry.resume || undefined}
              slotProps={{ primary: { noWrap: true }, secondary: { noWrap: true } }}
            />
            {entry.archived && <Chip size="small" label="archivée" sx={{ ml: 1 }} />}
            {entry.question && <Chip size="small" color="warning" label="question" sx={{ ml: 1 }} />}
          </ListItemButton>
        )
      })}
    </>
  )
}

export function MemoryTree(props: Props) {
  const root = buildTree(props.files)
  return (
    <List dense disablePadding aria-label="Arborescence de la mémoire">
      <Rows node={root} depth={0} {...props} />
    </List>
  )
}
