<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\UseCase\GetFinanceDashboard;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * `todo` carries what people owe the owner and what the owner owes them
 * (MAG-455): the « On te doit / Tu dois » pill of the Comptes tile.
 * World: `peer_debts_overview.yaml`.
 */
class GetFinanceDashboardPeerDebtsTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Api/fixtures/peer_debts_overview.yaml');
    }

    /** @return array<string, mixed> */
    private function todo(string $userRef): array
    {
        $now = new \DateTimeImmutable('now', new \DateTimeZone('Europe/Paris'));

        return self::getContainer()->get(GetFinanceDashboard::class)
            ->execute($this->getFixture($userRef), (int) $now->format('Y'), (int) $now->format('n'))['todo'];
    }

    public function testTodoCountsWhatIsOwedEachWayAndOnlyWhatIsLeft(): void
    {
        $todo = $this->todo('test_user');

        // The gîte 350 € still to come (Julie gave 50 € back), the petrol 280 €; the settled lunch is out.
        self::assertSame(35000, $todo['owedToMeCents']);
        self::assertSame(28000, $todo['iOweCents']);
    }

    public function testTodoNeverCountsSomeoneElsesDebts(): void
    {
        $todo = $this->todo('other_user');

        self::assertSame(4000, $todo['owedToMeCents']);
        self::assertSame(0, $todo['iOweCents']);
    }
}
