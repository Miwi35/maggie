import type { MemoryTreeEntry } from './memoryApi'

export interface TreeNode {
  name: string
  path: string
  entry?: MemoryTreeEntry
  children: TreeNode[]
}

/** The folders and files as the bucket lists them: nothing is regrouped, only nested by path. */
export function buildTree(files: MemoryTreeEntry[]): TreeNode {
  const root: TreeNode = { name: '', path: '', children: [] }
  for (const entry of files) {
    const segments = entry.path.split('/')
    let node = root
    segments.forEach((segment, index) => {
      const path = segments.slice(0, index + 1).join('/')
      const isFile = index === segments.length - 1
      let child = node.children.find((c) => c.name === segment && Boolean(c.entry) === isFile)
      if (!child) {
        child = { name: segment, path, children: [], ...(isFile ? { entry } : {}) }
        node.children.push(child)
      }
      node = child
    })
  }
  const sort = (node: TreeNode) => {
    node.children.sort((a, b) => {
      if (Boolean(a.entry) !== Boolean(b.entry)) return a.entry ? 1 : -1
      return a.name.localeCompare(b.name, 'fr')
    })
    node.children.forEach(sort)
  }
  sort(root)
  return root
}

export function ancestors(path: string): string[] {
  const segments = path.split('/').slice(0, -1)
  return segments.map((_, index) => segments.slice(0, index + 1).join('/'))
}
