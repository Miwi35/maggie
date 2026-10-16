<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Envelope;
use Maggie\Finance\UseCase\GetEnvelopeAdjustments;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * Which envelopes do not fit what was really spent, and what next month
 * should be — only for those (MAG-461). The world is
 * `envelope_adjustments.yaml`: September 2026, October is the month to set.
 */
class GetEnvelopeAdjustmentsTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private GetEnvelopeAdjustments $useCase;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->useCase = self::getContainer()->get(GetEnvelopeAdjustments::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures(dirname(__DIR__).'/Controller/fixtures/envelope_adjustments.yaml');
    }

    /** @return array<string, array<string, mixed>> the lines by category name */
    private function lines(string $today = '2026-09-28'): array
    {
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $data = $this->useCase->execute($user, new \DateTimeImmutable('2026-09-15'), new \DateTimeImmutable($today));

        self::assertSame('2026-09', $data['month']);
        self::assertSame('2026-10', $data['targetMonth']);

        $lines = [];
        foreach ($data['envelopes'] as $line) {
            $lines[$line['categoryName']] = $line;
        }

        return $lines;
    }

    public function testAnEnvelopeExceededThreeMonthsRunningAndKeptGetsARaiseWithItsReason(): void
    {
        $line = $this->lines()['Loisirs'];

        self::assertSame(24000, $line['actualCents']);
        self::assertSame(20000, $line['amountCents']);
        self::assertSame(20000, $line['targetAmountCents']);
        self::assertNotNull($line['targetEnvelopeId']);
        self::assertSame(25000, $line['suggestion']['proposedCents']);
        self::assertSame('raise', $line['suggestion']['direction']);
        self::assertNull($line['suggestion']['seasonal']);
        self::assertSame(
            'Dépassée 3 mois sur 3 (de 25 % en moyenne), dépenses surtout jugées à conserver : 250 € dépensés par mois en moyenne.',
            $line['suggestion']['reason'],
        );
    }

    public function testAnEnvelopeThatHoldsGetsNoSuggestion(): void
    {
        self::assertNull($this->lines()['Courses']['suggestion']);
    }

    public function testSpendingJudgedAvoidableIsNotMadeRoomFor(): void
    {
        $line = $this->lines()['Sorties'];

        self::assertSame(20000, $line['actualCents']);
        self::assertNull($line['suggestion']);
    }

    public function testAnEnvelopeLeftWellUnderItsAmountThreeMonthsRunningComesDown(): void
    {
        $line = $this->lines()['Cinéma'];

        self::assertNull($line['targetEnvelopeId']);
        // No October envelope yet: next month would roll over at the September amount.
        self::assertSame(4000, $line['targetAmountCents']);
        self::assertSame(2000, $line['suggestion']['proposedCents']);
        self::assertSame('lower', $line['suggestion']['direction']);
        self::assertSame('Tenue avec de la marge 3 mois sur 3 : 20 € au plus dépensés pour 40 € prévus.', $line['suggestion']['reason']);
    }

    public function testAMonthStillRunningIsNotReadAsLeftUnderBeforeTheReviewDay(): void
    {
        self::assertNull($this->lines('2026-09-10')['Cinéma']['suggestion']);
        self::assertNotNull($this->lines('2026-09-10')['Loisirs']['suggestion']);
    }

    public function testTheSameMonthOfThePastYearsMakesASeasonForThatMonthOnly(): void
    {
        $suggestion = $this->lines()['Cadeaux']['suggestion'];

        self::assertSame(10000, $suggestion['proposedCents']);
        self::assertSame('raise', $suggestion['direction']);
        self::assertSame(['fromMonth' => 10, 'toMonth' => 10], $suggestion['seasonal']);
        self::assertSame('En octobre, 100 € dépensés en moyenne ces 2 dernières années pour 70 € prévus.', $suggestion['reason']);
    }

    public function testNothingIsWrittenByReading(): void
    {
        $before = $this->em->getRepository(Envelope::class)->count([]);

        $this->lines();
        $this->em->clear();

        self::assertSame($before, $this->em->getRepository(Envelope::class)->count([]));
        $leisure = $this->em->getRepository(Envelope::class)->find($this->getFixture('leisure_oct')->getId());
        self::assertSame(20000, $leisure->getAmountCents());
    }
}
