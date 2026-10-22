import { Edit, useRecordContext } from 'react-admin'
import { EarlyRepaymentSimulation } from './EarlyRepaymentSimulation'
import { LoanBalanceChart } from './LoanBalanceChart'
import { LoanForm } from './LoanForm'
import { useLoanCurve } from './useLoanSimulation'
import { bareId } from './envelopeTree'

/** The form, then what the conditions mean in figures: the end, the capital curve, an early repayment. */
const LoanSheet = () => {
  const record = useRecordContext()
  const loanId = record ? bareId(record.id) : undefined
  // The figures are asked again whenever the stored loan changes.
  const version = JSON.stringify(record ?? {})
  const curve = useLoanCurve(loanId, version)

  return (
    <>
      <LoanForm endsOn={curve?.freedOn} />
      {loanId && record && (
        <>
          <EarlyRepaymentSimulation
            loanId={loanId}
            principalRemainingCents={record.principalRemainingCents as number}
            currency={record.currency as string}
            version={version}
          />
          {curve && curve.months.length > 0 && <LoanBalanceChart curve={curve} currency={record.currency as string} />}
        </>
      )}
    </>
  )
}

// Pessimistic and in place: a refused condition (the 400) shows at once, and
// the sheet stays on screen to be corrected and simulated again.
export const LoanEdit = () => (
  <Edit title="Modifier le prêt" mutationMode="pessimistic" redirect={false}>
    <LoanSheet />
  </Edit>
)
