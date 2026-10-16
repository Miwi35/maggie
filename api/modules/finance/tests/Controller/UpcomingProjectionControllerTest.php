<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Tests\UseCase\RecurringProjectionsWorld;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Dashboard › À venir over REST (MAG-463): a pure read. The one input, the
 * day read, is refused when malformed. Exemption: N/A — DB state, Mercure and
 * Elasticsearch, it writes nothing.
 */
class UpcomingProjectionControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use RecurringProjectionsWorld;

    private const string ENDPOINT = '/api/finance/upcoming';

    private KernelBrowser $client;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return array<string, mixed> */
    private function json(): array
    {
        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', self::ENDPOINT);

        self::assertResponseStatusCodeSame(401);
    }

    /** @return iterable<string, array{string}> */
    public static function badDates(): iterable
    {
        yield 'a month' => ['2026-10'];
        yield 'words' => ['demain'];
        yield 'a day that does not exist' => ['2026-02-30'];
        yield 'a list' => ['[]'];
    }

    #[\PHPUnit\Framework\Attributes\DataProvider('badDates')]
    public function testABadDateReturns400(string $date): void
    {
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/upcoming_projection.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', self::ENDPOINT, ['date' => $date], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testItGivesTheWeekTheMonthAndTheAdjustedEnvelopes(): void
    {
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/upcoming_projection.yaml');
        $this->series('Loyer', -90000, '2026-07-05');
        $this->series('Salaire ACME', 250000, '2026-07-25', categoryRef: 'salary');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', self::ENDPOINT, ['date' => '2026-10-16'], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = $this->json();
        self::assertSame('2026-10-16', $data['today']);
        self::assertSame('2026-10-19', $data['week']['from']);
        self::assertSame('2026-11', $data['month']['month']);
        self::assertSame(['Loyer'], array_column($data['month']['expenses'], 'label'));
        self::assertSame(['Courses', 'Logement', 'Loisirs'], array_column($data['month']['envelopes'], 'categoryName'));
        self::assertSame(250000 - (90000 + 40000 + 20000), $data['month']['remainingCents']);
    }

    public function testTodayIsTheDefaultDay(): void
    {
        $this->loadFixtures(__DIR__.'/../UseCase/fixtures/upcoming_projection.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        self::assertSame((new \DateTimeImmutable('today'))->format('Y-m-d'), $this->json()['today']);
    }
}
