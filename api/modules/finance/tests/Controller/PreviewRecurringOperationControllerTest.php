<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\RecurringOperation;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** What a series would recognise before it is saved (MAG-475). */
class PreviewRecurringOperationControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const string ENDPOINT = '/api/finance/recurring-operations/preview';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function preview(array $body): array
    {
        $this->client->request(
            'POST',
            self::ENDPOINT,
            [],
            [],
            $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'],
            json_encode($body, JSON_THROW_ON_ERROR),
        );

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    private function login(): void
    {
        $this->loadFixtures('amount_only.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', self::ENDPOINT, [], [], ['CONTENT_TYPE' => 'application/json'], json_encode(['referenceAmountCents' => 475000], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testASeriesWithoutAnAmountIsRefused(): void
    {
        $this->login();

        $this->preview(['counterpartyName' => 'Employeur XYZ']);

        self::assertResponseStatusCodeSame(400);
    }

    public function testAToleranceOutOfBoundsOrAFieldOfTheWrongTypeIsRefused(): void
    {
        $this->login();

        $this->preview(['referenceAmountCents' => 475000, 'amountTolerancePercent' => 150]);
        self::assertResponseStatusCodeSame(400);

        $this->preview(['referenceAmountCents' => '475000']);
        self::assertResponseStatusCodeSame(400);
    }

    public function testTheAmountAloneRecognisesTheSalaryWhateverItsLabelOrPayer(): void
    {
        $this->login();

        $data = $this->preview(['referenceAmountCents' => 475000, 'amountTolerancePercent' => 10]);

        self::assertResponseIsSuccessful();
        self::assertSame(2, $data['total']);
        self::assertEqualsCanonicalizing(
            ['VIR SEPA XYZ', 'SALAIRE SEPTEMBRE ABC'],
            array_column($data['matches'], 'label'),
            'neither the 3 000 € credit, the 4 750 € debit, the internal transfer nor another user\'s line',
        );
    }

    public function testAnIdentityNarrowsWhatTheAmountCatches(): void
    {
        $this->login();

        $data = $this->preview(['referenceAmountCents' => 475000, 'amountTolerancePercent' => 10, 'counterpartyName' => 'Employeur XYZ']);

        self::assertSame(['VIR SEPA XYZ'], array_column($data['matches'], 'label'));
    }

    public function testPreviewingWritesAndPublishesNothing(): void
    {
        $this->login();

        $this->preview(['referenceAmountCents' => 475000, 'amountTolerancePercent' => 10]);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(0, $em->getRepository(RecurringOperation::class)->findAll());
        $this->assertMercureUpdateCount(0);
    }
}
