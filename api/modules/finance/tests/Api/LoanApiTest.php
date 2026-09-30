<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class LoanApiTest extends WebTestCase
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

    /** @param array<string, mixed> $body */
    private function post(array $body, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request('POST', '/api/loans', [], [], $authenticated
            ? array_merge($headers, $this->authHeaders())
            : $headers, json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testCreateLoanRequiresAuthentication(): void
    {
        $this->loadFixtures('loan.yaml');

        $this->post([
            'name' => 'Prêt travaux',
            'principalRemainingCents' => 600000,
            'monthlyPaymentCents' => 50000,
        ], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testCreateLoanPersistsPublishesAndIndexes(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->post([
            'name' => 'Prêt travaux',
            'lender' => 'CIC',
            'principalRemainingCents' => 600000,
            'monthlyPaymentCents' => 50000,
            'annualRateBasisPoints' => 350,
            'priority' => 1,
            'currency' => 'EUR',
        ]);

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('Prêt travaux', $data['name']);
        self::assertSame(350, $data['annualRateBasisPoints']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(3, $em->getRepository(Loan::class)->findAll());

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testALoanWithoutNameIsRejected(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->post(['principalRemainingCents' => 600000, 'monthlyPaymentCents' => 50000]);

        self::assertResponseStatusCodeSame(422);
    }

    public function testALoanThatWouldNeverBeRepaidIsRejected(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->post([
            'name' => 'Piège',
            'principalRemainingCents' => 1000000,
            'monthlyPaymentCents' => 2000,
            'annualRateBasisPoints' => 500,
        ]);

        self::assertResponseStatusCodeSame(422);
    }

    /** @param array<string, mixed> $body */
    private function patch(string $id, array $body, bool $authenticated = true): void
    {
        $headers = [
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ];

        $this->client->request('PATCH', '/api/loans/' . $id, [], [], $authenticated
            ? array_merge($headers, $this->authHeaders())
            : $headers, json_encode($body, JSON_THROW_ON_ERROR));
    }

    public function testPatchLoanRequiresAuthentication(): void
    {
        $this->loadFixtures('loan.yaml');
        $loan = $this->getFixture('car');

        $this->patch((string) $loan->getId(), ['lender' => null], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullLenderClearsIt(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
        $loan = $this->getFixture('car');

        $this->patch((string) $loan->getId(), ['lender' => null]);

        self::assertResponseIsSuccessful();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $refreshed = $em->find(Loan::class, $loan->getId());
        self::assertNull($refreshed->getLender());
        self::assertSame(240000, $refreshed->getPrincipalRemainingCents());
        self::assertSame(20000, $refreshed->getMonthlyPaymentCents());

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchIndexDispatched(Loan::class);
    }

    public function testDeleteLoanRemovesAndPublishes(): void
    {
        $this->loadFixtures('loan.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $loan = $this->getFixture('car');
        $loanId = $loan->getId();

        $this->client->request('DELETE', '/api/loans/' . $loanId, [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));

        self::assertResponseStatusCodeSame(204);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Loan::class, $loanId));

        $this->assertMercureUpdatePublished('/loans/');
        $this->assertElasticsearchDeleteDispatched('loans');
    }
}
