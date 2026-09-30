<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use Mcp\Capability\RegistryInterface;
use Mcp\Schema\Tool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The tool list the agent sees, frozen into contract/mcp-tools.json.
 *
 * The agent calls these tools by name and fills their arguments from the
 * schema; nothing in PHP breaks when a tool is renamed or an argument
 * disappears. The failure surfaces as Maggie saying she cannot do something
 * she could do yesterday.
 *
 * The sharpest case is silent and has its own line in CLAUDE.md: a module
 * missing from `discovery.scan_dirs` in config/packages/mcp.yaml has all of
 * its tools hidden, with no error anywhere. Snapshotting the registry catches
 * that as forty-odd deleted lines in the diff.
 */
final class McpToolsContractTest extends KernelTestCase
{
    use ContractSnapshotTrait;

    /**
     * Every module that owns at least one tool. A bundle whose directory
     * drops out of `discovery.scan_dirs` loses its tools without a word, so
     * the presence of each module is asserted by name and not only through
     * the snapshot — the snapshot says "something changed", this says which
     * module went dark.
     */
    private const MODULE_TOOLS = [
        'calendar' => 'create_event',
        'cookbook' => 'create_recipe',
        'core' => 'search',
        'finance' => 'manage_accounts',
        'grocery' => 'add_grocery_item',
        'notification' => 'manage_notifications',
    ];

    public function testTheToolListMatchesTheContract(): void
    {
        $this->assertMatchesContract(
            'mcp-tools.json',
            $this->tools(),
            'A tool was added, renamed or removed, or one of their argument schemas changed. The agent addresses tools by name and fills arguments from these schemas, so check agent/ before regenerating.',
        );
    }

    public function testEveryModuleStillContributesItsTools(): void
    {
        $names = array_keys($this->tools());

        foreach (self::MODULE_TOOLS as $module => $tool) {
            self::assertContains($tool, $names, sprintf(
                'No tool named "%s" was discovered, which means the %s module contributes nothing. Check that "modules/%s/src" is still listed under discovery.scan_dirs in config/packages/mcp.yaml.',
                $tool,
                $module,
                $module,
            ));
        }
    }

    /**
     * A tool with no description is a tool the agent will not reach for, and
     * an argument schema without properties means the agent has to guess.
     * Neither breaks a PHP test; both break Maggie.
     */
    public function testEveryToolIsDescribedAndTakesADeclaredSchema(): void
    {
        foreach ($this->tools() as $name => $tool) {
            self::assertNotSame('', trim((string) ($tool['description'] ?? '')), sprintf('The MCP tool "%s" has no description.', $name));
            self::assertSame('object', $tool['inputSchema']['type'] ?? null, sprintf('The MCP tool "%s" does not declare an object input schema.', $name));
        }
    }

    /**
     * @return array<string, array<string, mixed>> tool name => tools/list entry
     */
    private function tools(): array
    {
        $container = self::getContainer();

        // Building the server is what runs the discovery loaders; asking the
        // registry first returns an empty list, which would make this whole
        // file pass against nothing.
        $container->get('mcp.server');

        $registry = $container->get('mcp.registry');
        self::assertInstanceOf(RegistryInterface::class, $registry);

        $tools = [];
        foreach ($registry->getTools()->references as $reference) {
            $tool = $reference instanceof Tool ? $reference : $reference->tool;
            $encoded = json_decode(json_encode($tool, \JSON_THROW_ON_ERROR), true, 512, \JSON_THROW_ON_ERROR);
            self::assertIsArray($encoded);

            $tools[$tool->name] = $encoded;
        }

        self::assertNotEmpty($tools, 'The MCP registry discovered no tool at all.');

        // The registry iterates in discovery order, which depends on the order
        // the filesystem hands back directory entries. Sorting keeps the
        // contract about the tools rather than about the machine that ran the
        // test.
        ksort($tools);

        return $tools;
    }
}
