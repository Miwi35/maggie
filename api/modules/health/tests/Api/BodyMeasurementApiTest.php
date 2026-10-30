<?php

namespace Maggie\Health\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Enum\MeasurementSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** The body measurements through the API Platform resource (MAG-706). */
class BodyMeasurementApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;

    private const ROWS = 7;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('body_measurement.yaml');
        $this->resetMercure();
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
        return '/api/body-measurements/'.$this->id($fixture);
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function reload(string $fixture): BodyMeasurement
    {
        $measurement = $this->em()->find(BodyMeasurement::class, $this->id($fixture));
        \assert($measurement instanceof BodyMeasurement);

        return $measurement;
    }

    private function rows(): int
    {
        return $this->em()->getRepository(BodyMeasurement::class)->count([]);
    }

    /** @return list<string> the ids listed by a GET on the collection */
    private function listed(string $query = ''): array
    {
        $this->send('GET', '/api/body-measurements'.$query);
        self::assertResponseIsSuccessful();

        return array_column($this->response()['member'], 'id');
    }

    /** @return array<string, mixed> */
    private static function body(array $override = []): array
    {
        return array_filter($override + [
            'measuredAt' => '2026-10-24T07:05:00+02:00',
            'weightKg' => 71.8,
            'bodyFatPercent' => 20.9,
        ], static fn (mixed $value): bool => '__unset' !== $value);
    }

    public function testCollectionRequiresAuthentication(): void
    {
        $this->send('GET', '/api/body-measurements', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateRequiresAuthentication(): void
    {
        $this->send('POST', '/api/body-measurements', self::body(), authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(self::ROWS, $this->rows());
    }

    public function testCreatePersistsAndPublishes(): void
    {
        $this->login();

        $this->send('POST', '/api/body-measurements', self::body());

        self::assertResponseStatusCodeSame(201);
        $data = $this->response();
        self::assertSame(71.8, $data['weightKg']);
        self::assertSame(20.9, $data['bodyFatPercent']);
        self::assertSame('manual', $data['source']);
        self::assertFalse($data['excluded']);

        $measurement = $this->em()->getRepository(BodyMeasurement::class)->find($data['id']);
        self::assertInstanceOf(BodyMeasurement::class, $measurement);
        self::assertSame(71.8, $measurement->getWeightKg());
        self::assertSame(20.9, $measurement->getBodyFatPercent());
        self::assertNull($measurement->getWaistCm());
        self::assertSame(MeasurementSource::Manual, $measurement->getSource());
        self::assertFalse($measurement->isExcluded());
        self::assertEquals(new \DateTimeImmutable('2026-10-24T07:05:00+02:00'), $measurement->getMeasuredAt());
        self::assertSame($this->id('test_user'), (string) $measurement->getUser()->getId());

        $payloads = $this->mercurePayloadsOn('/api/body-measurements/'.$measurement->getId());
        self::assertCount(1, $payloads);
        self::assertSame(71.8, $payloads[0]['weightKg']);
        self::assertFalse($payloads[0]['excluded']);
    }

    public function testAWaistSizeAloneIsAMeasurement(): void
    {
        $this->login();

        $this->send('POST', '/api/body-measurements', ['measuredAt' => '2026-10-24T07:05:00+02:00', 'waistCm' => 83]);

        self::assertResponseStatusCodeSame(201);
        self::assertEquals(83.0, $this->response()['waistCm']);
        self::assertSame(self::ROWS + 1, $this->rows());
    }

    public function testAMeasurementWithoutAnyFigureIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/body-measurements', ['measuredAt' => '2026-10-24T07:05:00+02:00']);

        self::assertResponseStatusCodeSame(422);
        self::assertStringContainsString('at least one', (string) $this->client->getResponse()->getContent());
        self::assertSame(self::ROWS, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testOutOfRangeFiguresAreRejected(): void
    {
        $this->login();

        foreach ([['weightKg' => 0], ['weightKg' => -1], ['weightKg' => 500.01], ['bodyFatPercent' => 0], ['bodyFatPercent' => 100], ['waistCm' => 0], ['waistCm' => 300.1]] as $override) {
            $this->send('POST', '/api/body-measurements', self::body($override));
            self::assertResponseStatusCodeSame(422, 'for '.json_encode($override));
        }

        self::assertSame(self::ROWS, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testTheBoundsThemselvesAreAccepted(): void
    {
        $this->login();

        $this->send('POST', '/api/body-measurements', self::body(['weightKg' => 500, 'bodyFatPercent' => 99.9, 'waistCm' => 300]));

        self::assertResponseStatusCodeSame(201);
    }

    public function testAMissingMomentOrOffsetIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/body-measurements', self::body(['measuredAt' => '__unset']));
        self::assertContains($this->client->getResponse()->getStatusCode(), [400, 422]);

        $this->send('POST', '/api/body-measurements', self::body(['measuredAt' => '2026-10-24T07:05:00']));
        self::assertResponseStatusCodeSame(422);
        self::assertStringContainsString('offset', (string) $this->client->getResponse()->getContent());

        self::assertSame(self::ROWS, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    public function testAnUnknownSourceOrAnUnreadableNumberIsABadRequest(): void
    {
        $this->login();

        $this->send('POST', '/api/body-measurements', self::body(['source' => 'scale']));
        self::assertResponseStatusCodeSame(400);

        $this->send('POST', '/api/body-measurements', self::body(['weightKg' => 'lourd']));
        self::assertResponseStatusCodeSame(400);

        self::assertSame(self::ROWS, $this->rows());
    }

    public function testTheCollectionIsTheUsersOwnMostRecentFirstInNumbers(): void
    {
        $this->login();

        $this->send('GET', '/api/body-measurements');

        self::assertResponseIsSuccessful();
        $members = $this->response()['member'];
        self::assertSame(
            [$this->id('boundary'), $this->id('imported'), $this->id('set_aside'), $this->id('evening'), $this->id('morning'), $this->id('waist')],
            array_column($members, 'id'),
        );

        $morning = $members[4];
        self::assertSame($this->iri('morning'), $morning['@id']);
        self::assertEquals(new \DateTimeImmutable('2026-10-21T07:12:00+02:00'), new \DateTimeImmutable($morning['measuredAt']));
        self::assertSame(72.4, $morning['weightKg']);
        self::assertSame(21.3, $morning['bodyFatPercent']);
        self::assertArrayNotHasKey('waistCm', $morning);
        self::assertSame('manual', $morning['source']);
        self::assertFalse($morning['excluded']);
        self::assertTrue($members[2]['excluded']);
        self::assertSame('health_connect', $members[1]['source']);
        self::assertSame('hc-1', $members[1]['externalId']);
        self::assertSame(84.5, $members[5]['waistCm']);
        self::assertArrayNotHasKey('weight', $morning);
    }

    public function testTheMomentFilterNarrowsTheList(): void
    {
        $this->login();

        self::assertSame([$this->id('boundary'), $this->id('imported'), $this->id('set_aside')], $this->listed('?measuredAt[after]=2026-10-22T00:00:00%2B02:00'));
        self::assertSame([$this->id('evening'), $this->id('morning'), $this->id('waist')], $this->listed('?measuredAt[before]=2026-10-22T00:00:00%2B02:00'));
    }

    public function testAPageOfAThousandServesAllTheMeasurements(): void
    {
        $this->login();
        $em = $this->em();
        $user = $em->find(User::class, $this->id('test_user'));
        \assert($user instanceof User);
        for ($i = 0; $i < 150; ++$i) {
            $em->persist((new BodyMeasurement())
                ->setUser($user)
                ->setMeasuredAt(new \DateTimeImmutable('2026-01-01T07:00:00+01:00'))
                ->setWeightKg(75.0));
        }
        $em->flush();

        $this->send('GET', '/api/body-measurements?itemsPerPage=1000');

        self::assertResponseIsSuccessful();
        self::assertCount(156, $this->response()['member']);
    }

    public function testAnotherUsersMeasurementIsNotFound(): void
    {
        $this->login();

        self::assertNotContains($this->id('secret'), $this->listed());

        $this->send('GET', $this->iri('secret'));
        self::assertResponseStatusCodeSame(404);

        $this->send('PATCH', $this->iri('secret'), ['weightKg' => 60]);
        self::assertResponseStatusCodeSame(404);

        $this->send('DELETE', $this->iri('secret'));
        self::assertResponseStatusCodeSame(404);

        self::assertSame(80.0, $this->reload('secret')->getWeightKg());
    }

    public function testPatchRequiresAuthentication(): void
    {
        $this->send('PATCH', $this->iri('morning'), ['excluded' => true], authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertFalse($this->reload('morning')->isExcluded());
    }

    /** « Écarter », then « Réintégrer ». */
    public function testPatchExcludesThenTakesBack(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('morning'), ['excluded' => true]);

        self::assertResponseIsSuccessful();
        $morning = $this->reload('morning');
        self::assertTrue($morning->isExcluded());
        self::assertSame(72.4, $morning->getWeightKg());
        self::assertSame(21.3, $morning->getBodyFatPercent());
        $payloads = $this->mercurePayloadsOn('/api/body-measurements/'.$this->id('morning'));
        self::assertCount(1, $payloads);
        self::assertTrue($payloads[0]['excluded']);

        $this->send('PATCH', $this->iri('morning'), ['excluded' => false]);

        self::assertResponseIsSuccessful();
        self::assertFalse($this->reload('morning')->isExcluded());
    }

    public function testPatchChangesWhatIsSentAndClearsWhatIsNull(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('morning'), ['weightKg' => 72.6, 'bodyFatPercent' => null]);

        self::assertResponseIsSuccessful();
        $morning = $this->reload('morning');
        self::assertSame(72.6, $morning->getWeightKg());
        self::assertNull($morning->getBodyFatPercent());
        self::assertArrayNotHasKey('bodyFatPercent', $this->response());
    }

    /** The admin sends the whole record read back, `id` an IRI (MAG-480). */
    public function testPatchSendingBackTheWholeRecordReadBeforeSavesIt(): void
    {
        $this->login();

        $this->send('GET', $this->iri('imported'));
        self::assertResponseIsSuccessful();
        $body = $this->response();
        $body['id'] = $body['@id'];
        $body['weightKg'] = 72.3;

        $this->send('PATCH', $this->iri('imported'), $body);

        self::assertResponseIsSuccessful();
        $imported = $this->reload('imported');
        self::assertSame(72.3, $imported->getWeightKg());
        self::assertSame('hc-1', $imported->getExternalId());
        self::assertSame(MeasurementSource::HealthConnect, $imported->getSource());
    }

    public function testPatchClearingTheOnlyFigureIsRejected(): void
    {
        $this->login();

        $this->send('PATCH', $this->iri('waist'), ['waistCm' => null]);

        self::assertResponseStatusCodeSame(422);
        self::assertSame(84.5, $this->reload('waist')->getWaistCm());
        $this->assertMercureUpdateCount(0);
    }

    public function testDeleteRequiresAuthentication(): void
    {
        $this->send('DELETE', $this->iri('morning'), authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertSame(self::ROWS, $this->rows());
    }

    public function testDeleteRemovesAndPublishes(): void
    {
        $this->login();

        $this->send('DELETE', $this->iri('morning'));

        self::assertResponseStatusCodeSame(204);
        self::assertNull($this->em()->find(BodyMeasurement::class, $this->id('morning')));
        self::assertSame(self::ROWS - 1, $this->rows());
        $this->assertMercureDeletePublished('/api/body-measurements/'.$this->id('morning'));
    }

    public function testTheSameExternalIdTwiceForAUserIsRejected(): void
    {
        $this->login();

        $this->send('POST', '/api/body-measurements', self::body(['externalId' => 'hc-1']));

        self::assertResponseStatusCodeSame(422);
        self::assertStringContainsString('externalId', (string) $this->client->getResponse()->getContent());
        self::assertSame(self::ROWS, $this->rows());
        $this->assertMercureUpdateCount(0);
    }

    /** hc-1 is both the user's and the other user's: unique per user only. */
    public function testANewExternalIdIsAccepted(): void
    {
        $this->login();

        $this->send('POST', '/api/body-measurements', self::body(['externalId' => 'hc-2']));

        self::assertResponseStatusCodeSame(201);
        self::assertSame(self::ROWS + 1, $this->rows());
    }
}
