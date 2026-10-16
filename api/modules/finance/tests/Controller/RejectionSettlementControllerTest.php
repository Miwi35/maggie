<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `POST` / `DELETE /api/finance/transactions/{id}/rejection-settlement` and
 * the incidents they take out of the pile (MAG-424).
 */
final class RejectionSettlementControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testUnauthenticatedReturns401OnBothMethods(): void
    {
        $this->loadFixtures('rejection_settlement.yaml');

        foreach (['POST', 'DELETE'] as $method) {
            $this->client->request($method, $this->path('edf_debit'));
            self::assertResponseStatusCodeSame(401, $method);
        }
        self::assertNull($this->stored('edf_debit')->getRejectionSettledAt());
    }

    public function testALineThatIsNotARejectionIsRefusedWith400(): void
    {
        $this->login();

        foreach (['POST', 'DELETE'] as $method) {
            $body = $this->call($method, 'groceries');
            self::assertResponseStatusCodeSame(400, $method);
            self::assertStringContainsString('not a rejected payment', $body['error']);
        }
        self::assertNull($this->stored('groceries')->getRejectionSettledAt());
    }

    public function testAnotherUsersOrUnknownTransactionIsReportedMissing(): void
    {
        $this->login();

        foreach ([$this->id('stranger_rejection'), '01ARZ3NDEKTSV4RRFFQ69G5FAV', 'not-a-ulid'] as $id) {
            $this->client->request('POST', "/api/finance/transactions/$id/rejection-settlement", [], [], $this->authHeaders());
            self::assertResponseStatusCodeSame(404, $id);
        }
        self::assertNull($this->stored('stranger_rejection')->getRejectionSettledAt());
    }

    public function testSettlingOneLegSettlesBothAndPublishes(): void
    {
        $this->login();

        $body = $this->call('POST', 'edf_debit');

        self::assertResponseIsSuccessful();
        self::assertTrue($body['success']);
        self::assertSame($this->id('edf_credit'), $body['counterpartId']);
        self::assertNotNull($body['rejectionSettledAt']);
        self::assertNotNull($this->stored('edf_debit')->getRejectionSettledAt());
        self::assertNotNull($this->stored('edf_credit')->getRejectionSettledAt());
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('edf_debit'));
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('edf_credit'));
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testSettlingTwiceKeepsTheFirstDate(): void
    {
        $this->login();

        $first = $this->call('POST', 'edf_credit')['rejectionSettledAt'];
        $second = $this->call('POST', 'edf_debit')['rejectionSettledAt'];

        self::assertResponseIsSuccessful();
        self::assertSame($first, $second);
    }

    public function testCancellingPutsItBackOnBothLegs(): void
    {
        $this->login();
        $this->call('POST', 'edf_debit');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $body = $this->call('DELETE', 'edf_credit');

        self::assertResponseIsSuccessful();
        self::assertNull($body['rejectionSettledAt']);
        self::assertNull($this->stored('edf_debit')->getRejectionSettledAt());
        self::assertNull($this->stored('edf_credit')->getRejectionSettledAt());
        $this->assertMercureUpdatePublished('/transactions/'.$this->id('edf_debit'));
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testASettledRejectionLeavesThePileAndShowsWithSettled(): void
    {
        $this->login();

        self::assertSame(
            [$this->id('edf_debit'), $this->id('gym_debit')],
            array_column($this->incidents(), 'debitId'),
        );

        $this->call('POST', 'edf_debit');

        self::assertSame([$this->id('gym_debit')], array_column($this->incidents(), 'debitId'));
        self::assertSame([null], array_column($this->incidents(), 'settledAt'));
        $settled = $this->incidents('?settled=true');
        self::assertSame([$this->id('edf_debit')], array_column($settled, 'debitId'));
        self::assertNotNull($settled[0]['settledAt']);

        $this->call('DELETE', 'edf_debit');

        self::assertSame(
            [$this->id('edf_debit'), $this->id('gym_debit')],
            array_column($this->incidents(), 'debitId'),
        );
        self::assertSame([], $this->incidents('?settled=true'));
    }

    public function testASingleLeggedRejectionIsSettledToo(): void
    {
        $this->login();

        $this->call('POST', 'gym_debit');

        self::assertResponseIsSuccessful();
        self::assertSame([$this->id('gym_debit')], array_column($this->incidents('?settled=true'), 'debitId'));
    }

    public function testABadSettledFilterIsRefusedWith400(): void
    {
        $this->login();

        $this->client->request('GET', '/api/accounts/'.$this->id('checking').'/incidents?settled=yes', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testUnmarkingTheRejectionForgetsTheSettlement(): void
    {
        $this->login();
        $this->call('POST', 'edf_debit');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $debit = $em->find(Transaction::class, $this->getFixture('edf_debit')->getId());
        $debit->releaseInternalTransfer(TransferSource::Manual);
        $em->flush();

        self::assertNull($this->stored('edf_debit')->getRejectionSettledAt());
        self::assertNull($this->stored('edf_credit')->getRejectionSettledAt());
    }

    private function login(): void
    {
        $this->loadFixtures('rejection_settlement.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function path(string $ref): string
    {
        return sprintf('/api/finance/transactions/%s/rejection-settlement', $this->id($ref));
    }

    /** @return array<string, mixed> */
    private function call(string $method, string $ref): array
    {
        $this->client->request($method, $this->path($ref), [], [], $this->authHeaders());

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return list<array<string, mixed>> */
    private function incidents(string $query = ''): array
    {
        $this->client->request('GET', '/api/accounts/'.$this->id('checking').'/incidents'.$query, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['incidents'];
    }

    private function stored(string $ref): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Transaction::class, $this->getFixture($ref)->getId());
    }
}
