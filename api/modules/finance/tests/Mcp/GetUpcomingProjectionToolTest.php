<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Mcp\Tool\GetUpcomingProjectionTool;
use Maggie\Finance\Tests\UseCase\RecurringProjectionsWorld;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * « Que me restera-t-il le mois prochain ? » (MAG-463). A pure read: it
 * publishes and indexes nothing.
 */
class GetUpcomingProjectionToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;
    use RecurringProjectionsWorld;

    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/upcoming_projection.yaml');
    }

    /** @return array<string, mixed> */
    private function read(?string $date = '2026-10-16'): array
    {
        $tool = self::getContainer()->get(GetUpcomingProjectionTool::class);

        return json_decode($tool($date), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        self::assertArrayHasKey('error', $this->read());
    }

    public function testABadDateIsReported(): void
    {
        $this->loginFixtureUser();

        self::assertSame(['error' => 'date must be a day as YYYY-MM-DD'], $this->read('2026-13-01'));
    }

    public function testItReadsTheWeekTheMonthAndTheRemainder(): void
    {
        $this->series('Loyer', -90000, '2026-07-05');
        $this->series('Salaire ACME', 250000, '2026-07-25', categoryRef: 'salary');
        $this->loginFixtureUser();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->read();

        self::assertSame('2026-10-19', $data['week']['from']);
        self::assertSame(['Loyer'], array_column($data['month']['expenses'], 'label'));
        self::assertSame(250000, $data['month']['incomeCents']);
        self::assertSame(100000, $data['month']['remainingCents']);
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }

    public function testAUserNeverReadsTheProjectionOfAnother(): void
    {
        $this->loginFixtureUser('other_user');

        $data = $this->read();

        self::assertSame([], $data['month']['envelopes']);
        self::assertSame(0, $data['month']['envelopesCents']);
    }
}
