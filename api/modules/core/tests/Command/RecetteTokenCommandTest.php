<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\Command\SmokeTokenCommand;
use Maggie\Core\Entity\User;
use Maggie\Core\Service\RecetteAccount;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;

final class RecetteTokenCommandTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->purgeDatabase();
        $this->resetAsyncTransport();

        $this->tester = new CommandTester((new Application(self::$kernel))->find('app:recette:token'));
    }

    public function testItCreatesTheAccountWithTheProactionPermissionOnFirstUse(): void
    {
        self::assertSame(0, $this->countAccounts(RecetteAccount::EMAIL));

        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        $user = $this->account(RecetteAccount::EMAIL);
        self::assertNotNull($user);
        self::assertSame(RecetteAccount::NAME, $user->getName());
        self::assertSame(RecetteAccount::GOOGLE_ID, $user->getGoogleId());
        self::assertContains(User::ROLE_PROACTION_TRIGGER, $user->getRoles());
        $this->assertElasticsearchIndexDispatched(User::class);
    }

    public function testItReusesTheAccountOnTheNextRun(): void
    {
        $this->tester->execute([]);
        $firstId = (string) $this->account(RecetteAccount::EMAIL)->getId();

        $this->tester->execute([]);

        self::assertSame(1, $this->countAccounts(RecetteAccount::EMAIL));
        self::assertSame($firstId, (string) $this->account(RecetteAccount::EMAIL)->getId());
    }

    public function testItGivesTheRoleBackToAnAccountWhosePermissionWasRevoked(): void
    {
        $this->tester->execute([]);
        $user = $this->account(RecetteAccount::EMAIL);
        $user->removeRole(User::ROLE_PROACTION_TRIGGER);
        $this->entityManager()->flush();

        $this->tester->execute([]);

        self::assertContains(User::ROLE_PROACTION_TRIGGER, $this->account(RecetteAccount::EMAIL)->getRoles());
    }

    public function testItAsksForTheIndexOnEveryRun(): void
    {
        $this->tester->execute([]);
        $this->resetAsyncTransport();

        $this->tester->execute([]);

        $this->assertElasticsearchIndexDispatched(User::class);
    }

    public function testStdoutCarriesTheTokenAlone(): void
    {
        $this->tester->execute([]);

        self::assertMatchesRegularExpression('/^[\w-]+\.[\w-]+\.[\w-]+$/', trim($this->tester->getDisplay()));
    }

    public function testTheTokenIdentifiesTheAccountAndCarriesItsPermission(): void
    {
        $this->tester->execute([]);

        $claims = self::getContainer()->get(JWTTokenManagerInterface::class)->parse(trim($this->tester->getDisplay()));

        self::assertSame(RecetteAccount::EMAIL, $claims['username'] ?? null);
        self::assertSame((string) $this->account(RecetteAccount::EMAIL)->getId(), $claims['sub'] ?? null);
        self::assertContains(User::ROLE_PROACTION_TRIGGER, $claims['roles'] ?? []);
    }

    public function testItLeavesEveryOtherAccountAlone(): void
    {
        $this->loadFixtures('SmokeTokenCommandTest.yaml');

        $this->tester->execute([]);

        $owner = $this->account('owner@maggie.local');
        self::assertSame(['ROLE_USER'], $owner->getRoles());
        self::assertSame(2, $this->entityManager()->getRepository(User::class)->count([]));
    }

    public function testSmokeAndRecetteAreTwoDistinctAccountsAndOnlyRecetteIsPrivileged(): void
    {
        $smoke = new CommandTester((new Application(self::$kernel))->find('app:smoke:token'));
        $smoke->execute([]);
        $this->tester->execute([]);

        self::assertSame(2, $this->entityManager()->getRepository(User::class)->count([]));
        self::assertSame(['ROLE_USER'], $this->account(SmokeTokenCommand::EMAIL)->getRoles());
        self::assertNotSame(trim($smoke->getDisplay()), trim($this->tester->getDisplay()));
    }

    private function account(string $email): ?User
    {
        $this->entityManager()->clear();

        return $this->entityManager()->getRepository(User::class)->findOneBy(['email' => $email]);
    }

    private function countAccounts(string $email): int
    {
        return $this->entityManager()->getRepository(User::class)->count(['email' => $email]);
    }

    private function entityManager(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
