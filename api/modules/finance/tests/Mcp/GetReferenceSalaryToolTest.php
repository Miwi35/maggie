<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Mcp\Tool\GetReferenceSalaryTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** The tool takes no argument: a pure read, so the user it is bound to is all that can be wrong. */
class GetReferenceSalaryToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    private const string FIXTURES = __DIR__.'/../UseCase/fixtures/reference_salary.yaml';

    protected function setUp(): void
    {
        self::bootKernel();
    }

    public function testWithoutAUserBoundItReportsTheError(): void
    {
        $this->loadFixtures(self::FIXTURES);

        self::assertArrayHasKey('error', $this->read());
    }

    public function testItReadsTheMarkAndItsSource(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->loginFixtureUser();

        $data = $this->read();

        self::assertSame(495000, $data['monthlyCents']);
        self::assertSame('expected', $data['source']);
        self::assertSame(['Salaire ACME'], array_column($data['operations'], 'label'));
    }

    public function testAUserNeverReadsTheSalaryOfAnother(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->loginFixtureUser('declared_user');

        $data = $this->read();

        self::assertSame(520000, $data['monthlyCents']);
        self::assertSame(['Salaire Initech'], array_column($data['operations'], 'label'));
        self::assertStringNotContainsString('ACME', json_encode($data, JSON_THROW_ON_ERROR));
    }

    public function testNothingKnownIsUnknown(): void
    {
        $this->loadFixtures(self::FIXTURES);
        $this->loginFixtureUser('empty_user');

        $data = $this->read();

        self::assertNull($data['monthlyCents']);
        self::assertSame('unknown', $data['source']);
    }

    /** @return array<string, mixed> */
    private function read(): array
    {
        $tool = self::getContainer()->get(GetReferenceSalaryTool::class);

        return json_decode($tool(), true, 512, JSON_THROW_ON_ERROR);
    }
}
