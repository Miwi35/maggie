import { Create } from 'react-admin'
import { CategorizationRuleForm } from './CategorizationRuleForm'

export const CategorizationRuleCreate = () => (
  <Create title="Nouvelle règle">
    <CategorizationRuleForm withDefaults />
  </Create>
)
