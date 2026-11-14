// spechub CLI —— 类 git 的 SpecHub 同步工具（G 组）。
package main

import (
	"fmt"
	"os"

	"spechub/cli/internal/cli"
)

func main() {
	if err := cli.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "spechub:", err)
		os.Exit(1)
	}
}