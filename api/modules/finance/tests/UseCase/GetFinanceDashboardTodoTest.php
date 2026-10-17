<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * What waits for the owner on the finance home (MAG-421): `todo` counts the
 * same things the screens it opens show, and only the user's own.
 */
class GetFinanceDashboardTodoTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SavingsAdvancesWorld;

    private GetFinanceDashboard $useCase;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->useCase = self::getContainer()->get(GetFinanceDashboard::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('savings_advances.yaml');
    }

    /** @return array<string, mixed> */
    private function dashboard(string $userRef = 'test_user'): array
    {
        $now = new \DateTimeImmutable();

        return $this->useCase->execute($this->user($userRef), (int) $now->format('Y'), (int) $now->format('n'));
    }

    private function today(): string
    {
        return (new \DateTimeImmutable())->format('Y-m-d');
    }

    private function lastMonth(): string
    {
        return (new \DateTimeImmutable('first day of last month'))->format('Y-m-d');
    }

    /** A rejected payment on an account: the debit and the credit that gives it back. */
    private function rejection(string $accountRef, int $amountCents): \Maggie\Finance\Entity\Transaction
    {
        $debit = $this->add($accountRef, -$amountCents, $this->today(), label: 'PRELEVEMENT EDF');
        $credit = $this->add($accountRef, $amountCents, $this->today(), label: 'REJET PRLV EDF');
        $debit->markAsRejection($credit, TransferSource::Auto);
        $this->flushWithoutTransactionEffects($this->em);

        return $debit;
    }

    public function testNothingToDoIsZeroEverywhere(): void
    {
        $data = $this->dashboard();

        self::assertSame(
            ['toSortCount' => 0, 'rejectionsToSettleCount' => 0, 'reviewReady' => false, 'advanceToRepayCents' => 0],
            $data['todo'],
        );
        self::assertSame([0], array_values(array_unique(array_column($data['tiles']['accounts']['accounts'], 'rejectionsToSettleCount'))));
    }

    public function testItCountsTheLinesToSortTheRejectionsAndTheAdvanceToGiveBack(): void
    {
        $this->add('checking', -1200, $this->today(), label: 'CB BOULANGERIE');
        $this->add('checking', -3400, $this->today(), label: 'CB LIBRAIRIE');
        $this->add('joint', 500, $this->today(), label: 'VIR INCONNU');
        $this->rejection('checking', 20600);
        $this->transfer('livret', 'checking', 80000, $this->today(), TransferKind::Advance);

        $data = $this->dashboard();

        self::assertSame(3, $data['todo']['toSortCount']);
        self::assertSame(1, $data['todo']['rejectionsToSettleCount']);
        self::assertSame(80000, $data['todo']['advanceToRepayCents']);

        $byName = array_column($data['tiles']['accounts']['accounts'], 'rejectionsToSettleCount', 'name');
        self::assertSame(1, $byName['Courant']);
        self::assertSame(0, $byName['Livret A']);
    }

    public function testASettledRejectionLeavesTheCountAndAnUndoBringsItBack(): void
    {
        $debit = $this->rejection('checking', 20600);
        self::assertSame(1, $this->dashboard()['todo']['rejectionsToSettleCount']);

        $debit->settleRejection(new \DateTimeImmutable());
        $this->flushWithoutTransactionEffects($this->em);
        $settled = $this->dashboard();

        self::assertSame(0, $settled['todo']['rejectionsToSettleCount']);
        self::assertSame(0, array_column($settled['tiles']['accounts']['accounts'], 'rejectionsToSettleCount', 'name')['Courant']);

        $debit->settleRejection(null);
        $this->flushWithoutTransactionEffects($this->em);

        self::assertSame(1, $this->dashboard()['todo']['rejectionsToSettleCount']);
    }

    public function testARepaidAdvanceIsNoLongerToGiveBack(): void
    {
        [$advance] = $this->transfer('livret', 'checking', 80000, $this->lastMonth(), TransferKind::Advance);
        $this->repay($advance, 'livret', 30000, $this->today());

        self::assertSame(50000, $this->dashboard()['todo']['advanceToRepayCents']);

        $this->repay($advance, 'livret', 50000, $this->today());

        self::assertSame(0, $this->dashboard()['todo']['advanceToRepayCents']);
    }

    public function testTheReviewIsReadyWhileALineOfTheMonthJustEndedIsUnrated(): void
    {
        $spend = $this->add('checking', -4500, $this->lastMonth(), 'bonus', 'CB RESTAURANT');

        self::assertTrue($this->dashboard()['todo']['reviewReady']);

        $spend->setRetrospect(RetrospectVerdict::Keep);
        $this->flushWithoutTransactionEffects($this->em);

        self::assertFalse($this->dashboard()['todo']['reviewReady']);
    }

    public function testThePresentMonthIsNotReviewedYet(): void
    {
        $this->add('checking', -4500, $this->today(), 'bonus', 'CB RESTAURANT');

        self::assertFalse($this->dashboard()['todo']['reviewReady']);
    }

    public function testItNeverCountsSomeoneElsesThings(): void
    {
        $this->add('other_checking', -1200, $this->today(), label: 'CB BOULANGERIE');
        $this->transfer('other_livret', 'other_checking', 80000, $this->today(), TransferKind::Advance);

        $todo = $this->dashboard()['todo'];

        self::assertSame(0, $todo['toSortCount']);
        self::assertSame(0, $todo['advanceToRepayCents']);
    }
}
