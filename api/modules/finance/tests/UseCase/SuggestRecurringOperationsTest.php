<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\UseCase\SuggestRecurringOperations;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The series the statement implies: which payees come back, how often, for
 * how much — and which never are proposed (MAG-47).
 */
class SuggestRecurringOperationsTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private SuggestRecurringOperations $suggest;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->suggest = self::getContainer()->get(SuggestRecurringOperations::class);
        $this->loadFixtures('recurring_suggestions.yaml');
    }

    private function user(): User
    {
        /** @var User $user */
        $user = $this->getFixture('test_user');

        return $user;
    }

    /** @return array<string, array<string, mixed>> the suggestions by label */
    private function byLabel(int $minOccurrences = 3): array
    {
        return array_column($this->suggest->suggest($this->user(), $minOccurrences), null, 'label');
    }

    public function testThreeDebitsOnTheSameDayAreAMonthlySeriesAnchoredOnTheFirst(): void
    {
        $flixo = $this->byLabel()['Flixo'];

        self::assertSame('monthly', $flixo['period']);
        self::assertSame('2026-07-12', $flixo['anchorOn']);
        self::assertSame('fixed_day', $flixo['dayRule']);
        self::assertSame(-1349, $flixo['referenceAmountCents']);
        self::assertSame(0, $flixo['amountSpreadPercent']);
        self::assertSame(3, $flixo['occurrences']);
        // The payee the bank names, which is what the series will recognise its lines by.
        self::assertSame('Flixo', $flixo['counterpartyName']);
        self::assertSame('flixo', $flixo['counterpartyKey']);
        self::assertNull($flixo['labelPattern']);
        self::assertSame('Courant', $flixo['accountName']);
    }

    public function testASuggestionTakesTheHeadingTheOwnerAlreadyGave(): void
    {
        $flixo = $this->byLabel()['Flixo'];

        self::assertSame('Abonnements', $flixo['categoryName']);
        self::assertSame((string) $this->getFixture('subscriptions')->getId(), $flixo['categoryId']);
    }

    public function testWithoutACounterpartyTheMerchantReadInTheLabelStandsIn(): void
    {
        $rent = $this->byLabel()['LOYER SCI VERDA'];

        self::assertSame('monthly', $rent['period']);
        self::assertSame('2026-07-05', $rent['anchorOn']);
        self::assertSame(-90000, $rent['referenceAmountCents']);
        self::assertNull($rent['counterpartyName']);
        self::assertNull($rent['counterpartyKey']);
        self::assertSame('LOYER SCI VERDA', $rent['labelPattern']);
        self::assertNull($rent['categoryId']);
    }

    public function testASkippedMonthIsStillMonthly(): void
    {
        $gym = $this->byLabel()['CLUB FORME'];

        // The mean gap is 40 days: only the median keeps it monthly.
        self::assertSame('monthly', $gym['period']);
        self::assertSame('2026-01-01', $gym['anchorOn']);
        self::assertSame(4, $gym['occurrences']);
    }

    public function testQuarterlyAndYearlySeriesAreRecognised(): void
    {
        $suggestions = $this->byLabel();

        self::assertSame('quarterly', $suggestions['ASSURANCE HABITATION']['period']);
        self::assertSame('yearly', $suggestions['SYNDICAT']['period']);
        self::assertSame('2024-03-10', $suggestions['SYNDICAT']['anchorOn']);
    }

    public function testTheLastDayOfTheMonthIsRecognisedWhateverTheMonthLength(): void
    {
        $salary = $this->byLabel()['ACME INDUSTRIE'];

        self::assertSame('last_day_of_month', $salary['dayRule']);
        self::assertSame(250000, $salary['referenceAmountCents']);
        self::assertSame('monthly', $salary['period']);
    }

    public function testIrregularGapsAndWanderingAmountsAreNotASeries(): void
    {
        $suggestions = $this->byLabel();

        // 5, 40 and 12 days.
        self::assertArrayNotHasKey('BOULANGERIE DU COIN', $suggestions);
        // Monthly, but from 20 € to 200 €.
        self::assertArrayNotHasKey('GARAGE MARTIN', $suggestions);
    }

    public function testTwoOccurrencesAreNotEnoughEvenWhenAskedFor(): void
    {
        self::assertArrayNotHasKey('SALLE ESCALADE', $this->byLabel(2));
    }

    public function testALoanInstalmentIsNeverSuggested(): void
    {
        self::assertArrayNotHasKey('REMBOURSEMENT DE PRET', $this->byLabel());
    }

    public function testASavingsTopUpIsNeverSuggested(): void
    {
        $labels = array_keys($this->byLabel());

        self::assertSame([], array_values(array_filter($labels, static fn (string $label) => str_contains($label, 'LIVRET'))));
    }

    public function testLinesWaitingForArbitrationAreNotHistory(): void
    {
        self::assertArrayNotHasKey('CANTINE ECOLE', $this->byLabel());
    }

    public function testAPayeeAlreadyCoveredByASeriesIsLeftOut(): void
    {
        self::assertArrayNotHasKey('ASSURANCE AUTO', $this->byLabel());
        self::assertArrayNotHasKey('Assurance Auto', $this->byLabel());
    }

    public function testOnlyTheUsersOwnHistoryCounts(): void
    {
        $flixo = $this->byLabel()['Flixo'];

        self::assertSame(-1349, $flixo['referenceAmountCents']);
        self::assertSame(3, $flixo['occurrences']);
    }

    public function testTheBiggestAmountsComeFirstWithThreeSamples(): void
    {
        $suggestions = $this->suggest->suggest($this->user());

        $amounts = array_map(static fn (array $s) => abs($s['referenceAmountCents']), $suggestions);
        $sorted = $amounts;
        rsort($sorted);
        self::assertSame($sorted, $amounts);
        self::assertSame('ACME INDUSTRIE', $suggestions[0]['label']);

        $gym = array_column($suggestions, null, 'label')['CLUB FORME'];
        self::assertCount(3, $gym['samples']);
        // The latest first.
        self::assertSame('PRELEVEMENT CLUB FORME', $gym['samples'][0]);
    }

    public function testSuggestingWritesNothing(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $before = \count($em->getRepository(RecurringOperation::class)->findAll());

        $this->suggest->suggest($this->user());

        self::assertCount($before, $em->getRepository(RecurringOperation::class)->findAll());
        self::assertSame(1, $before);
        self::assertSame(0, (int) $em->getRepository(Transaction::class)->count(['recurringSource' => 'manual']));
    }

    public function testAcceptingCreatesTheSeriesAndAttachesItsHistory(): void
    {
        $flixo = $this->byLabel()['Flixo'];

        $result = $this->suggest->accept($this->user(), [$flixo]);

        self::assertSame(1, $result['created']);
        self::assertSame(['Flixo'], $result['labels']);
        self::assertSame(3, $result['attached']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $series = $em->getRepository(RecurringOperation::class)->findOneBy(['label' => 'Flixo']);
        self::assertNotNull($series);
        self::assertSame('flixo', $series->getCounterpartyKey());
        self::assertSame(-1349, $series->getReferenceAmountCents());
        self::assertSame('2026-07-12', $series->getAnchorOn()->format('Y-m-d'));
        self::assertCount(3, $em->getRepository(Transaction::class)->findBy(['recurringOperation' => $series]));
    }

    public function testAcceptedPayeeIsNoLongerSuggested(): void
    {
        $this->suggest->accept($this->user(), [['categoryId' => (string) $this->getFixture('housing')->getId()] + $this->byLabel()['LOYER SCI VERDA']]);

        self::assertArrayNotHasKey('LOYER SCI VERDA', $this->byLabel());
    }

    public function testAnEntryWithoutACategoryIsIgnored(): void
    {
        $rent = $this->byLabel()['LOYER SCI VERDA'];

        $result = $this->suggest->accept($this->user(), [$rent]);

        self::assertSame(0, $result['created']);
        self::assertSame(1, self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(RecurringOperation::class)->count([]));
    }

    public function testAnEntryWithAnotherUsersCategoryOrAccountIsIgnored(): void
    {
        $flixo = $this->byLabel()['Flixo'];

        $foreignCategory = $this->suggest->accept($this->user(), [['categoryId' => (string) $this->getFixture('other_secret')->getId()] + $flixo]);
        $foreignAccount = $this->suggest->accept($this->user(), [['accountId' => (string) $this->getFixture('other_checking')->getId()] + $flixo]);

        self::assertSame(0, $foreignCategory['created']);
        self::assertSame(0, $foreignAccount['created']);
    }

    public function testAMalformedEntryIsIgnoredAndTheOthersAreCreated(): void
    {
        $flixo = $this->byLabel()['Flixo'];

        $result = $this->suggest->accept($this->user(), [
            ['period' => 'fortnightly'] + $flixo,
            ['anchorOn' => '12/07/2026'] + $flixo,
            ['referenceAmountCents' => '1349'] + $flixo,
            ['label' => 'Sans identité', 'counterpartyName' => null, 'labelPattern' => null] + $flixo,
            $flixo,
        ]);

        self::assertSame(1, $result['created']);
    }

    public function testAcceptingTwiceCreatesOneSeries(): void
    {
        $flixo = $this->byLabel()['Flixo'];

        $this->suggest->accept($this->user(), [$flixo, $flixo]);
        $again = $this->suggest->accept($this->user(), [$flixo]);

        self::assertSame(0, $again['created']);
        self::assertSame(2, self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(RecurringOperation::class)->count([]));
    }
}
