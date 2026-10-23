<?php

namespace Maggie\Finance\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\ProjectInvestment;
use Maggie\Finance\Enum\FundingSourceKind;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** An investment: a down payment, a loan, a rent and a planned resale (MAG-557). */
class ProjectInvestmentApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('project_investment.yaml');
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
        $headers = ['CONTENT_TYPE' => 'application/json', 'HTTP_ACCEPT' => 'application/json'];

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

    private function investment(string $project = 'box'): string
    {
        return '/api/finance/projects/'.$this->id($project).'/investment';
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function reload(string $fixture = 'box'): Project
    {
        $project = $this->em()->find(Project::class, $this->id($fixture));
        \assert($project instanceof Project);

        return $project;
    }

    /** @return array<string, mixed> 4 000 € down, 14 000 € at 3,9 % over 10 years, 160 € of rent, resold in June 2028 */
    private function box(bool $withResale = true): array
    {
        return array_filter([
            'downPaymentCents' => 400000,
            'loan' => ['annualRateBasisPoints' => 390, 'durationMonths' => 120],
            'monthlyRentCents' => 16000,
            'resale' => $withResale ? ['month' => '2028-06', 'netAmountCents' => 1000000, 'targetProjectId' => $this->id('car')] : null,
        ], static fn (mixed $value): bool => null !== $value);
    }

    public function testSettingTheInvestmentRequiresAuthentication(): void
    {
        $this->send('PUT', $this->investment(), $this->box(), authenticated: false);
        self::assertResponseStatusCodeSame(401);

        $this->send('DELETE', $this->investment(), authenticated: false);
        self::assertResponseStatusCodeSame(401);
    }

    public function testAnotherUsersProjectIsNotFound(): void
    {
        $this->login();

        $this->send('PUT', $this->investment('secret'), $this->box(false));
        self::assertResponseStatusCodeSame(404);

        $this->send('DELETE', $this->investment('secret'));
        self::assertResponseStatusCodeSame(404);
    }

    public function testTheBoxIsAnInvestmentWithItsPaymentAndMonthlyBalance(): void
    {
        $this->login();

        $this->send('PUT', $this->investment(), $this->box());

        self::assertResponseIsSuccessful();
        $investment = $this->response()['project']['investment'];
        self::assertSame($this->id('box'), $investment['projectId']);
        self::assertSame(1800000, $investment['priceCents']);
        self::assertSame(400000, $investment['downPaymentCents']);
        // 14 000 € at 3,9 % over 120 months: 141,08 €; the rent of 160 € leaves 18,92 €.
        self::assertSame(14108, $investment['loanPaymentCents']);
        self::assertSame(16000, $investment['monthlyRentCents']);
        self::assertSame(1892, $investment['monthlyBalanceCents']);
        self::assertSame(['month' => '2028-06', 'netAmountCents' => 1000000, 'targetProjectId' => $this->id('car')], $investment['resale']);

        $box = $this->reload();
        self::assertSame(400000, $box->getSalaryCents());
        $stored = $box->getInvestment();
        self::assertInstanceOf(ProjectInvestment::class, $stored);
        self::assertSame(16000, $stored->getMonthlyRentCents());
        self::assertSame('2028-06', $stored->getResale()['month'] ?? null);
        $loan = $box->loanSource()?->getLoan();
        self::assertInstanceOf(Loan::class, $loan);
        self::assertTrue($loan->isProjected());
        self::assertSame(1400000, $loan->getInitialAmountCents());
        self::assertSame($investment['loanId'], (string) $loan->getId());

        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('box'));
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('car'));
        $this->assertElasticsearchIndexDispatched(Project::class);
    }

    public function testTheResaleFeedsTheTargetsDownPaymentAndShiftsItsPlanning(): void
    {
        $this->login();
        $this->send('GET', '/api/finance/project-planning');
        self::assertResponseIsSuccessful();
        $before = $this->planningRow('car');
        self::assertNull($before['resaleFromMonth']);

        $this->send('PUT', $this->investment(), $this->box());
        self::assertResponseIsSuccessful();

        $car = $this->reload('car');
        $resale = array_values(array_filter($car->getFundingSources(), static fn ($source): bool => FundingSourceKind::Resale === $source->getKind()));
        self::assertCount(1, $resale);
        self::assertSame(1000000, $resale[0]->getAmountCents());
        self::assertSame($this->id('box'), $resale[0]->getSourceProjectId());
        self::assertSame(200000, $car->getSalaryCents());

        $this->send('GET', '/api/finance/project-planning');
        $after = $this->planningRow('car');
        self::assertSame('2028-06', $after['resaleFromMonth']);
        self::assertSame('2028-06', $this->planningRow('box')['investment']['resaleMonth']);
        self::assertSame('2028-05', $this->planningRow('box')['investment']['rentUntilMonth']);
        self::assertSame('2028-05', $this->planningRow('box')['loan']['endMonth']);
        self::assertLessThan($before['purchaseMonth'] ?? '9999-99', '2028-06', 'The car is bought long before without the resale.');
        self::assertSame('2028-06', $after['purchaseMonth']);
    }

    /** @return array<string, mixed> */
    private function planningRow(string $fixture): array
    {
        foreach ($this->response()['projects'] as $row) {
            if ($this->id($fixture) === $row['projectId']) {
                return $row;
            }
        }

        throw new \LogicException("No {$fixture} in the planning.");
    }

    public function testPlanningAnotherResaleMovesTheSourceAndRemovingItTakesItBack(): void
    {
        $this->login();
        $this->send('PUT', $this->investment(), $this->box());
        self::assertResponseIsSuccessful();
        $this->resetMercure();

        $body = $this->box();
        $body['resale'] = ['month' => '2029-01', 'netAmountCents' => 500000, 'targetProjectId' => $this->id('bike')];
        $this->send('PUT', $this->investment(), $body);

        self::assertResponseIsSuccessful();
        self::assertSame(1200000, $this->reload('car')->getSalaryCents());
        // The bike (1 200 €) takes what it needs of the 5 000 €.
        self::assertSame(0, $this->reload('bike')->getSalaryCents());
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('car'));
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('bike'));

        $body['resale'] = null;
        $this->send('PUT', $this->investment(), $body);
        self::assertResponseIsSuccessful();
        self::assertNull($this->response()['project']['investment']['resale']);
        self::assertSame(120000, $this->reload('bike')->getSalaryCents());
    }

    public function testRemovingTheInvestmentTakesTheResaleBackAndKeepsTheFunding(): void
    {
        $this->login();
        $this->send('PUT', $this->investment(), $this->box());
        self::assertResponseIsSuccessful();
        $this->resetMercure();

        $this->send('DELETE', $this->investment());

        self::assertResponseIsSuccessful();
        self::assertNull($this->response()['project']['investment']);
        $box = $this->reload();
        self::assertNull($box->getInvestment());
        self::assertNotNull($box->loanSource());
        self::assertSame(1200000, $this->reload('car')->getSalaryCents());
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('box'));
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('car'));

        $this->send('DELETE', $this->investment());
        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('not an investment', $this->response()['error']);
    }

    public function testDeletingTheTargetKeepsTheInvestmentWithoutTarget(): void
    {
        $this->login();
        $this->send('PUT', $this->investment(), $this->box());
        self::assertResponseIsSuccessful();

        $this->send('DELETE', '/api/finance/projects/'.$this->id('car'));
        self::assertResponseStatusCodeSame(204);

        $investment = $this->reload()->getInvestment();
        self::assertNotNull($investment);
        self::assertSame(['month' => '2028-06', 'netAmountCents' => 1000000, 'targetProjectId' => null], $investment->getResale());
    }

    public function testDeletingTheInvestmentRemovesTheResaleItSetAside(): void
    {
        $this->login();
        $this->send('PUT', $this->investment(), $this->box());
        self::assertResponseIsSuccessful();

        $this->send('DELETE', '/api/finance/projects/'.$this->id('box'));
        self::assertResponseStatusCodeSame(204);

        self::assertSame(1200000, $this->reload('car')->getSalaryCents());
        self::assertCount(0, $this->em()->getRepository(ProjectInvestment::class)->findAll());
    }

    public function testASourceListSentBackKeepsTheResale(): void
    {
        $this->login();
        $this->send('PUT', $this->investment(), $this->box());
        self::assertResponseIsSuccessful();

        $this->send('GET', '/api/finance/projects/'.$this->id('car'));
        $sources = $this->response()['fundingSources'];
        self::assertSame(['kind' => 'resale', 'amountCents' => 1000000, 'accountId' => null, 'loanId' => null, 'sourceProjectId' => $this->id('box')], $sources[0]);

        // The client echoes what it read, then adds nothing: the resale stays.
        $this->send('PUT', '/api/finance/projects/'.$this->id('car').'/funding', ['sources' => $sources]);

        self::assertResponseIsSuccessful();
        self::assertSame(['resale'], array_column($this->response()['project']['fundingSources'], 'kind'));
        self::assertSame(200000, $this->reload('car')->getSalaryCents());
    }

    public function testBadInputIsRefusedWithTheCurrentProject(): void
    {
        $this->login();

        $cases = [
            'missing down payment' => [['monthlyRentCents' => 16000], 'downPaymentCents'],
            'missing rent' => [['downPaymentCents' => 400000, 'loan' => $this->box()['loan']], 'monthlyRentCents'],
            'down payment above the price' => [['downPaymentCents' => 1900000, 'monthlyRentCents' => 0], 'more than the price'],
            'negative rent' => [['downPaymentCents' => 400000, 'monthlyRentCents' => -1, 'loan' => $this->box()['loan']], 'monthlyRentCents'],
            'loan without its conditions' => [['downPaymentCents' => 400000, 'monthlyRentCents' => 16000], 'conditions'],
            'loan for nothing borrowed' => [['downPaymentCents' => 1800000, 'monthlyRentCents' => 0, 'loan' => $this->box()['loan']], 'nothing is borrowed'],
            'unknown field' => [$this->box(false) + ['colour' => 'red'], 'Unknown investment field'],
            'resale month' => [['resale' => ['month' => '2028-6', 'netAmountCents' => 1]] + $this->box(false), 'YYYY-MM'],
            'resale amount' => [['resale' => ['month' => '2028-06', 'netAmountCents' => 0]] + $this->box(false), 'netAmountCents'],
            'resale for itself' => [['resale' => ['month' => '2028-06', 'netAmountCents' => 1, 'targetProjectId' => $this->id('box')]] + $this->box(false), 'itself'],
            'resale for a stranger' => [['resale' => ['month' => '2028-06', 'netAmountCents' => 1, 'targetProjectId' => $this->id('secret')]] + $this->box(false), 'Project not found'],
        ];

        foreach ($cases as $label => [$body, $message]) {
            $this->resetMercure();
            $this->send('PUT', $this->investment(), $body);

            self::assertResponseStatusCodeSame(400, $label);
            $data = $this->response();
            self::assertStringContainsString($message, $data['error'], $label);
            self::assertNull($data['current']['investment'], $label);
            self::assertNull($this->reload()->getInvestment(), $label);
            self::assertNull($this->reload()->loanSource(), $label);
            $this->assertMercureUpdateCount(0);
        }

        $this->client->request('PUT', $this->investment(), [], [], array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders()), '{nope');
        self::assertResponseStatusCodeSame(400);
    }

    public function testAResaleThatWouldPayForAProjectTheInvestmentWaitsForIsRefused(): void
    {
        $this->login();
        // The box is funded by the car's resale…
        $this->send('PUT', $this->investment('car'), ['downPaymentCents' => 1200000, 'monthlyRentCents' => 0, 'resale' => ['month' => '2028-06', 'netAmountCents' => 500000, 'targetProjectId' => $this->id('box')]]);
        self::assertResponseIsSuccessful();

        // …so the box cannot be resold for the car, which would wait for the box that waits for it.
        $this->send('PUT', $this->investment('box'), ['downPaymentCents' => 500000] + $this->box());

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('waits for', $this->response()['error']);
        self::assertNull($this->reload()->getInvestment());
    }

    public function testOtherSourcesOfTheProjectCountInTheDownPayment(): void
    {
        $this->login();
        // 4 000 € of down payment cannot hold the 5 000 € the car's resale already sets aside for the box.
        $this->send('PUT', $this->investment('car'), ['downPaymentCents' => 1200000, 'monthlyRentCents' => 0, 'resale' => ['month' => '2028-06', 'netAmountCents' => 500000, 'targetProjectId' => $this->id('box')]]);
        self::assertResponseIsSuccessful();

        $this->send('PUT', $this->investment('box'), ['downPaymentCents' => 400000, 'monthlyRentCents' => 16000, 'loan' => $this->box()['loan']]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('other sources', $this->response()['error']);
    }
}
