import { FunctionField } from 'react-admin'
import type { RaRecord } from 'react-admin'
import { categoryLabel } from './categoryTree'
import type { CategoryNode } from './categoryTree'

/** A category, named by its path: « Loisirs › Bars › Obsydian ». For use as a `ReferenceField` child or on a category record. */
export const CategoryPathField = ({ label }: { label?: string }) => (
  <FunctionField label={label} render={(record: RaRecord) => categoryLabel(record as unknown as CategoryNode)} />
)
