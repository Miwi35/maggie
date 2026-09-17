import { Edit } from 'react-admin'
import { CategorizationRuleForm } from './CategorizationRuleForm'

export const CategorizationRuleEdit = () => (
  <Edit title="Modifier la règle">
    <CategorizationRuleForm />
  </Edit>
)
