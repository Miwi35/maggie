<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Placement;
use Maggie\Finance\Enum\PlacementFrequency;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The simulated placements through the API Platform resource (MAG-555). */
class PlacementApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('placement.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function login(): void
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
    }

    /** @param array<string, mixed>|null $body */
    private function send(string $method, string $uri, ?array $body = null, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'PATCH' === $method ? 'application/merge-patch+json' : 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request(
            $method,
            $uri,
            [],
            [],
            $authenticated ? array_merge($headers, $this->authHeaders()) : $headers,
            null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR),
        );
    }

    /** @return array<string, mixed> */
    private function response(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function iri(string $fixture): string
    {
        return '/api/finance/placements/'.$this->id($fixture);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function reload(string $fixture): Placement
    {
        $placement = $this->em()->find(Placement::class, $this->id($fixture));
        \assert($placement instanceof Placement);

        return $placement;
    }

    private function rows(): int
    {
        return $this->em()->getRepository(Placement::class)->count([]);
    }

    public function testCollectionRequiresAuthentication(): void
    {
        $this->send('GET', '/api/finance/placements', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateRequiresAuthentication(): void
    {
        $this->send('POST', '/api/finance/placements', ['name' => 'PEA', 'deposit' => 10000], authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(3, $this->rows());
    }

    public function testCreatePersistsPublishesIndexesAndSimulates(): void
    {
        $this->login();

        // The e2e journey's placement: 100 € a month at 5 % over 10 years.
        $this->send('POST', '/api/finance/placements', [
            'name' => 'Épargne de Léo',
            'initial' => 0,
            'deposit' => 10000,
            'frequency' => 'monthly',
            'annualRate' => 500,
            'years' => 10,
        ]);

        self::assertResponseStatusCodeSame(201);
        $data = $this->response();
        self::assertSame('Épargne de Léo', $data['name']);
        self::assertSame('monthly', $data['frequency']);
        self::assertSame(500, $data['annualRate']);
        // 100 × ((1,05^10 − 1) / r), r = 1,05^(1/12) − 1: 15 436,32 €.
        self::assertEqualsWithDelta(1543632, $data['final'], 100);
        self::assertSame(120 * 10000, $data['paid']);
        self::assertSame($data['final'] - $data['paid'], $data['interest']);
        self::assertSame((int) round($data['final'] * 0.04 / 12), $data['rent']);
        self::assertSame(10000, $data['monthlyDeposit']);

        $placement = $this->em()->getRepository(Placement::class)->findOneBy(['name' => 'Épargne de Léo']);
        self::assertInstanceOf(Placement::class, $placement);
        self::assertSame(0, $placement->getInitial());
        self::assertSame(10000, $placement->getDeposit());
        self::assertSame(PlacementFrequency::Monthly, $placement->getFrequency());
        self::assertSame(500, $placement->getAnnualRate());
        self::assertSame(10, $placement->getYears());
        self::assertSame($this->id('test_user'), (string) $placement->getUser()->getId());

        $this->assertMercureUpdatePublished('/api/finance/placements/'.$placement->getId());
        $this->assertElasticsearchIndexDispatched(Placement::class);
    }

    public function testCreateWithoutNameIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/placements', ['deposit' => 10000]);

        self::assertResponseStatusCodeSame(422);
        self::assertSame(3, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAPlacementWithNothingPaidIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/placements', ['name' => 'Vide', 'initial' => 0, 'deposit' => 0]);

        self::assertResponseStatusCodeSame(422);
        self::assertStringContainsString(Placement::NOTHING_PAID, (string) $this->client->getResponse()->getContent());
        self::assertSame(3, $this->rows());
    }

    public function testOutOfRangeFiguresAreRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/placements', ['name' => 'PEA', 'deposit' => -100]);
        self::assertResponseStatusCodeSame(422);

        $this->send('POST', '/api/finance/placements', ['name' => 'PEA', 'deposit' => 100, 'years' => 0]);
        self::assertResponseStatusCodeSame(422);

        $this->send('POST', '/api/finance/placements', ['name' => 'PEA', 'deposit' => 100, 'annualRate' => 10001]);
        self::assertResponseStatusCodeSame(422);

        self::assertSame(3, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnUnknownFrequencyIsABadRequest(): void
    {
        $this->login();

        $this->send('POST', '/api/finance/placements', ['name' => 'PEA', 'deposit' => 100, 'frequency' => 'weekly']);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(3, $this->rows());
    }

    public function testTheCollectionIsTheUsersOwnWithTheirSimulation(): void
    {
        $this->login();

        $this->send('GET', '/api/finance/placements');

        self::assertResponseIsSuccessful();
        $members = $this->response()['member'];
        self::assertSame(['Assurance-vie', 'PEA'], array_column($members, 'name'));
        self::assertNotContains('Placement secret', array_column($members, 'name'));

        $pea = $members[1];
        self::assertSame($this->iri('pea'), $pea['@id']);
        self::assertSame($this->id('pea'), $pea['id']);
        self::assertSame(200000, $pea['initial']);
        self::assertSame(15000, $pea['deposit']);
        self::assertSame(600, $pea['annualRate']);
        self::assertSame(20, $pea['years']);
        self::assertEqualsWithDelta(7443007, $pea['final'], 100);

        self::assertSame('yearly', $members[0]['frequency']);
        self::assertSame(10000, $members[0]['monthlyDeposit']);
    }

    public function testAnotherUsersPlacementIsNotFound(): void
    {
        $this->login();

        $this->send('GET', $this->iri('secret'));
        self::assertResponseStatusCodeSame(404);

        $this->send('PATCH', $this->iri('secret'), ['name' => 'Volé']);
        self::assertResponseStatusCodeSame(404);

        $this->send('DELETE', $this->iri('secret'));
        self::assertResponseStatusCodeSame(404);

        self::assertSame('Placement secret', $this->reload('secret')->getName());
    }

    public function testPatchRequiresAuthentication(): void
    {
        $this->send('PATCH', $this->iri('pea'), ['years' => 25], authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(20, $this->reload('pea')->getYears());
    }

    public function testPatchChangesOnlyWhatIsSentAndRepublishesTheFigures(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('pea'), ['years' => 25, 'frequency' => 'quarterly']);

        self::assertResponseIsSuccessful();
        $pea = $this->reload('pea');
        self::assertSame(25, $pea->getYears());
        self::assertSame(PlacementFrequency::Quarterly, $pea->getFrequency());
        self::assertSame(15000, $pea->getDeposit());
        self::assertSame(100 * 15000 + 200000, $this->response()['paid']);

        $payloads = $this->mercurePayloadsOn('/api/finance/placements/'.$this->id('pea'));
        self::assertCount(1, $payloads);
        self::assertSame($pea->getFinal(), $payloads[0]['final']);
        self::assertSame(5000, $payloads[0]['monthlyDeposit']);
        $this->assertElasticsearchIndexDispatched(Placement::class);
    }

    /** The admin sends the whole record read back, `id` an IRI, computed fields included (MAG-480). */
    public function testPatchSendingBackTheWholeRecordReadBeforeSavesIt(): void
    {
        $this->login();

        $this->send('GET', $this->iri('pea'));
        self::assertResponseIsSuccessful();
        $body = $this->response();
        $body['id'] = $body['@id'];
        $body['deposit'] = 20000;

        $this->send('PATCH', $this->iri('pea'), $body);

        self::assertResponseIsSuccessful();
        self::assertSame(20000, $this->reload('pea')->getDeposit());
    }

    public function testPatchEmptyingAPlacementIsRejected(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('life'), ['deposit' => 0]);

        self::assertResponseStatusCodeSame(422);
        self::assertSame(120000, $this->reload('life')->getDeposit());
        $this->assertMercureUpdateCount(0);
    }

    public function testDeleteRequiresAuthentication(): void
    {
        $this->send('DELETE', $this->iri('pea'), authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(3, $this->rows());
    }

    public function testDeleteRemovesPublishesAndUnindexes(): void
    {
        $this->login();

        $this->send('DELETE', $this->iri('pea'));

        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->em()->find(Placement::class, $this->id('pea')));
        self::assertSame(2, $this->rows());
        $this->assertMercureDeletePublished('/api/finance/placements/'.$this->id('pea'));
        $this->assertElasticsearchDeleteDispatched('placements');
    }
}
