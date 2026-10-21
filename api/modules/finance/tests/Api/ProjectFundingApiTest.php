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
use Maggie\Finance\Entity\ProjectFundingSource;
use Maggie\Finance\Enum\FundingSourceKind;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** A project's funding sources and its priced options (MAG-524). */
class ProjectFundingApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('project_funding.yaml');
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

    private function funding(string $project = 'car'): string
    {
        return '/api/finance/projects/'.$this->id($project).'/funding';
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->clear();

        return $em;
    }

    private function reload(string $fixture = 'car'): Project
    {
        $project = $this->em()->find(Project::class, $this->id($fixture));
        \assert($project instanceof Project);

        return $project;
    }

    /** @return list<Loan> */
    private function projectedLoans(): array
    {
        return array_values($this->em()->getRepository(Loan::class)->findBy(['projected' => true]));
    }

    /** @return array<string, mixed> 1 000 € from the Livret jeune, 6 000 € borrowed over 24 months at 4 % */
    private function carFunding(): array
    {
        return ['sources' => [
            ['kind' => 'savings', 'amountCents' => 100000, 'account' => '/api/accounts/'.$this->id('livret_jeune')],
            ['kind' => 'loan', 'amountCents' => 600000, 'loan' => ['annualRateBasisPoints' => 400, 'durationMonths' => 24]],
        ]];
    }

    /** @return array<string, mixed> */
    private function savingCapacity(): array
    {
        $this->send('GET', '/api/finance/debt-timeline');
        self::assertResponseIsSuccessful();

        return $this->response()['savingCapacity'];
    }

    public function testSettingTheFundingRequiresAuthentication(): void
    {
        $this->send('PUT', $this->funding(), $this->carFunding(), authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testTheOptionsRequireAuthentication(): void
    {
        $this->send('GET', '/api/finance/projects/'.$this->id('car').'/options', authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnotherUsersProjectIsNotFound(): void
    {
        $this->login();

        $this->send('PUT', $this->funding('secret'), ['sources' => []]);
        self::assertResponseStatusCodeSame(404);

        $this->send('GET', '/api/finance/projects/'.$this->id('secret').'/options?loanRateBasisPoints=400&loanMonths=24&downPaymentCents=0&yieldBasisPoints=0');
        self::assertResponseStatusCodeSame(404);
    }

    public function testSavingsAndALoanLeaveTheRestToTheSalaryAndProjectALoan(): void
    {
        $this->login();
        $capacityBefore = $this->savingCapacity();
        $this->send('GET', '/api/finance/debt-timeline');
        $timelineBefore = $this->response();

        $this->send('PUT', $this->funding(), $this->carFunding());

        self::assertResponseIsSuccessful();
        $project = $this->response()['project'];
        self::assertSame(300000, $project['salaryCents']);
        self::assertCount(2, $project['fundingSources']);
        self::assertSame(['kind' => 'savings', 'amountCents' => 100000, 'accountId' => $this->id('livret_jeune'), 'loanId' => null], $project['fundingSources'][0]);
        self::assertSame('loan', $project['fundingSources'][1]['kind']);
        self::assertNull($project['fundingSources'][1]['accountId']);

        $loans = $this->projectedLoans();
        self::assertCount(1, $loans);
        self::assertSame($project['fundingSources'][1]['loanId'], (string) $loans[0]->getId());
        self::assertSame(600000, $loans[0]->getPrincipalRemainingCents());
        self::assertSame(600000, $loans[0]->getInitialAmountCents());
        self::assertSame(26055, $loans[0]->getMonthlyPaymentCents());
        self::assertSame(24, $loans[0]->getDurationMonths());
        self::assertSame(400, $loans[0]->getAnnualRateBasisPoints());

        $reloaded = $this->reload();
        self::assertSame(300000, $reloaded->getSalaryCents());
        self::assertCount(2, $reloaded->getFundingSources());

        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('car'));
        $this->assertMercureUpdatePublished('/api/loans/'.$loans[0]->getId());
        $this->assertElasticsearchIndexDispatched(Project::class);
        $this->assertElasticsearchIndexDispatched(Loan::class);

        // A projected loan is not signed: the capacity and the timeline do not move.
        self::assertSame($capacityBefore, $this->savingCapacity());
        $this->send('GET', '/api/finance/debt-timeline');
        self::assertSame($timelineBefore, $this->response());
    }

    public function testTheProjectServesItsSourcesAndItsSalaryPart(): void
    {
        $this->login();
        $this->send('PUT', $this->funding(), $this->carFunding());
        self::assertResponseIsSuccessful();

        $this->send('GET', '/api/finance/projects/'.$this->id('car'));

        self::assertResponseIsSuccessful();
        $data = $this->response();
        self::assertSame(300000, $data['salaryCents']);
        self::assertCount(2, $data['fundingSources']);
        self::assertSame(['kind', 'amountCents', 'accountId', 'loanId'], array_keys($data['fundingSources'][0]));

        // The projected loan is in the loans, flagged.
        $this->send('GET', '/api/loans');
        self::assertResponseIsSuccessful();
        $projected = array_values(array_filter($this->response(), static fn (array $loan): bool => true === $loan['projected']));
        self::assertCount(1, $projected);
        self::assertSame(600000, $projected[0]['principalRemainingCents']);
    }

    public function testSourcesAboveTheAmountAreRefusedWithTheCurrentOnes(): void
    {
        $this->login();
        $this->send('PUT', $this->funding(), $this->carFunding());
        self::assertResponseIsSuccessful();
        $this->resetMercure();

        $this->send('PUT', $this->funding(), ['sources' => [
            ['kind' => 'savings', 'amountCents' => 500000, 'account' => $this->id('livret_jeune')],
            ['kind' => 'loan', 'amountCents' => 600000, 'loan' => ['annualRateBasisPoints' => 400, 'durationMonths' => 24]],
        ]]);

        self::assertResponseStatusCodeSame(400);
        $data = $this->response();
        self::assertStringContainsString('1100000', $data['error']);
        self::assertSame(300000, $data['current']['salaryCents']);
        self::assertSame(100000, $data['current']['fundingSources'][0]['amountCents']);
        self::assertSame(600000, $this->reload()->loanSource()?->getAmountCents());
        $this->assertMercureUpdateCount(0);
    }

    public function testRemovingTheLoanSourceRemovesTheProjectedLoan(): void
    {
        $this->login();
        $this->send('PUT', $this->funding(), $this->carFunding());
        self::assertResponseIsSuccessful();
        $loanId = (string) $this->projectedLoans()[0]->getId();
        $this->resetMercure();

        $this->send('PUT', $this->funding(), ['sources' => [
            ['kind' => 'savings', 'amountCents' => 100000, 'account' => $this->id('livret_jeune')],
        ]]);

        self::assertResponseIsSuccessful();
        self::assertSame(900000, $this->response()['project']['salaryCents']);
        self::assertSame([], $this->projectedLoans());
        self::assertCount(1, $this->reload()->getFundingSources());
        $this->assertMercureUpdatePublished('/api/finance/projects/'.$this->id('car'));
        $this->assertMercureUpdatePublished('/api/loans/'.$loanId);
    }

    public function testALoanSourceSentWithoutConditionsKeepsItsLoanForTheNewAmount(): void
    {
        $this->login();
        $this->send('PUT', $this->funding(), $this->carFunding());
        self::assertResponseIsSuccessful();
        $loanId = (string) $this->projectedLoans()[0]->getId();

        $this->send('PUT', $this->funding(), ['sources' => [
            ['kind' => 'loan', 'amountCents' => 1000000],
        ]]);

        self::assertResponseIsSuccessful();
        self::assertSame(0, $this->response()['project']['salaryCents']);
        $loans = $this->projectedLoans();
        self::assertCount(1, $loans);
        self::assertSame($loanId, (string) $loans[0]->getId());
        self::assertSame(1000000, $loans[0]->getPrincipalRemainingCents());
        self::assertSame(43425, $loans[0]->getMonthlyPaymentCents());
        self::assertSame(24, $loans[0]->getDurationMonths());
    }

    public function testCapitalComesFromAnInvestment(): void
    {
        $this->login();

        $this->send('PUT', $this->funding(), ['sources' => [
            ['kind' => 'capital', 'amountCents' => 200000, 'account' => '/api/accounts/'.$this->id('pea')],
        ]]);

        self::assertResponseIsSuccessful();
        self::assertSame(800000, $this->response()['project']['salaryCents']);
        $source = $this->reload()->getFundingSources()[0];
        self::assertSame(FundingSourceKind::Capital, $source->getKind());
        self::assertSame($this->id('pea'), $source->getAccountId());
    }

    /** @return iterable<string, array{0: \Closure(self): array<string, mixed>, 1: string}> */
    public static function refusedSources(): iterable
    {
        yield 'the cushion' => [fn (self $t) => ['kind' => 'savings', 'amountCents' => 1000, 'account' => $t->id('livret_a')], 'cushion'];
        yield 'another user\'s account' => [fn (self $t) => ['kind' => 'savings', 'amountCents' => 1000, 'account' => $t->id('other_livret')], 'Account not found'];
        yield 'savings without an account' => [fn (self $t) => ['kind' => 'savings', 'amountCents' => 1000], 'account is required'];
        yield 'savings from an investment' => [fn (self $t) => ['kind' => 'savings', 'amountCents' => 1000, 'account' => $t->id('pea')], 'savings or checking'];
        yield 'capital from a savings account' => [fn (self $t) => ['kind' => 'capital', 'amountCents' => 1000, 'account' => $t->id('livret_jeune')], 'investment'];
        yield 'a loan without its conditions' => [fn (self $t) => ['kind' => 'loan', 'amountCents' => 1000], 'annualRateBasisPoints, durationMonths'];
        yield 'a loan with half its conditions' => [fn (self $t) => ['kind' => 'loan', 'amountCents' => 1000, 'loan' => ['annualRateBasisPoints' => 400]], 'durationMonths'];
        yield 'an unknown kind' => [fn (self $t) => ['kind' => 'gift', 'amountCents' => 1000], 'kind'];
        yield 'a zero amount' => [fn (self $t) => ['kind' => 'savings', 'amountCents' => 0, 'account' => $t->id('livret_jeune')], 'amountCents'];
    }

    /** @param \Closure(self): array<string, mixed> $source */
    #[\PHPUnit\Framework\Attributes\DataProvider('refusedSources')]
    public function testABadSourceIsRefusedAndNothingIsWritten(\Closure $source, string $reason): void
    {
        $this->login();

        $this->send('PUT', $this->funding(), ['sources' => [$source($this)]]);

        self::assertResponseStatusCodeSame(400);
        $data = $this->response();
        self::assertStringContainsString($reason, $data['error']);
        self::assertSame([], $data['current']['fundingSources']);
        self::assertSame(1000000, $data['current']['salaryCents']);
        self::assertSame(0, $this->em()->getRepository(ProjectFundingSource::class)->count([]));
        self::assertSame([], $this->projectedLoans());
        $this->assertMercureUpdateCount(0);
    }

    public function testABodyWithoutSourcesIsRefused(): void
    {
        $this->login();

        $this->send('PUT', $this->funding(), ['kind' => 'savings']);

        self::assertResponseStatusCodeSame(400);
        self::assertArrayHasKey('current', $this->response());
    }

    public function testDeletingTheProjectRemovesItsProjectedLoan(): void
    {
        $this->login();
        $this->send('PUT', $this->funding(), $this->carFunding());
        self::assertResponseIsSuccessful();

        $this->send('DELETE', '/api/finance/projects/'.$this->id('car'));

        self::assertResponseStatusCodeSame(204);
        self::assertSame([], $this->projectedLoans());
        self::assertSame(0, $this->em()->getRepository(ProjectFundingSource::class)->count([]));
    }

    public function testTheOptionsArePricedOnTheProjectsAmount(): void
    {
        $this->login();

        $this->send('GET', '/api/finance/projects/'.$this->id('car').'/options?loanRateBasisPoints=400&loanMonths=24&downPaymentCents=400000&yieldBasisPoints=300');

        self::assertResponseIsSuccessful();
        $data = $this->response();
        self::assertSame(50000, $data['netCapacityCents']);

        self::assertSame(1000000, $data['cash']['totalCostCents']);
        self::assertSame(0, $data['cash']['monthlyPaymentCents']);
        self::assertSame(20, $data['cash']['availableInMonths']);

        self::assertSame(43425, $data['loan']['monthlyPaymentCents']);
        self::assertEqualsWithDelta(1042200, $data['loan']['totalCostCents'], 100);
        self::assertEqualsWithDelta(61800, $data['loan']['yieldCents'], 100);
        self::assertEqualsWithDelta(-19600, $data['loan']['netCostCents'], 100);
        self::assertSame(0, $data['loan']['availableInMonths']);
        self::assertSame(1000000, $data['loan']['capitalKeptCents']);

        self::assertSame(600000, $data['partial']['principalCents']);
        self::assertSame(26055, $data['partial']['monthlyPaymentCents']);
        self::assertEqualsWithDelta(1025300, $data['partial']['totalCostCents'], 100);
        self::assertEqualsWithDelta(37100, $data['partial']['yieldCents'], 100);
        self::assertEqualsWithDelta(-11800, $data['partial']['netCostCents'], 100);
        self::assertSame(8, $data['partial']['availableInMonths']);
        self::assertSame(600000, $data['partial']['capitalKeptCents']);

        foreach (['cash', 'loan', 'partial'] as $option) {
            self::assertNull($data[$option]['nImpactMonths']);
            foreach (['totalCostCents', 'netCostCents', 'monthlyPaymentCents', 'durationMonths', 'availableInMonths', 'capitalKeptCents'] as $field) {
                self::assertArrayHasKey($field, $data[$option]);
            }
        }
        self::assertStringNotContainsStringIgnoringCase('recommend', (string) $this->client->getResponse()->getContent());
    }

    public function testTheOptionsWithoutLoanConditionsListWhatIsMissing(): void
    {
        $this->login();

        $this->send('GET', '/api/finance/projects/'.$this->id('car').'/options?downPaymentCents=400000&yieldBasisPoints=300');

        self::assertResponseStatusCodeSame(400);
        $error = $this->response()['error'];
        self::assertStringContainsString('loanRateBasisPoints', $error);
        self::assertStringContainsString('loanMonths', $error);
        self::assertStringNotContainsString('yieldBasisPoints', $error);
    }

    public function testTheOptionsReadTheLoanSourcesConditions(): void
    {
        $this->login();
        $this->send('PUT', $this->funding(), $this->carFunding());
        self::assertResponseIsSuccessful();

        // No rate, no duration, no down payment: the loan source gives them, 4 000 € not borrowed.
        $this->send('GET', '/api/finance/projects/'.$this->id('car').'/options?yieldBasisPoints=300');

        self::assertResponseIsSuccessful();
        $data = $this->response();
        self::assertSame(400000, $data['downPaymentCents']);
        self::assertSame(43425, $data['loan']['monthlyPaymentCents']);
        self::assertSame(26055, $data['partial']['monthlyPaymentCents']);
    }

    public function testAMalformedParameterIsRefused(): void
    {
        $this->login();

        $this->send('GET', '/api/finance/projects/'.$this->id('car').'/options?loanRateBasisPoints=4%25&loanMonths=24&downPaymentCents=0&yieldBasisPoints=0');

        self::assertResponseStatusCodeSame(400);
    }

    public function testTheOptionsWithoutSavingCapacityCannotSayWhen(): void
    {
        $this->login();
        // No income known: no capacity.
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);
        $em->remove($this->getFixture('cushion'));
        $em->flush();

        $this->send('GET', '/api/finance/projects/'.$this->id('car').'/options?loanRateBasisPoints=400&loanMonths=24&downPaymentCents=400000&yieldBasisPoints=0');

        self::assertResponseIsSuccessful();
        $data = $this->response();
        self::assertNull($data['cash']['availableInMonths']);
        self::assertNull($data['partial']['availableInMonths']);
        self::assertSame(0, $data['loan']['availableInMonths']);
    }
}
