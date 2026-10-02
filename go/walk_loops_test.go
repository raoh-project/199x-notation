package notation199x

import (
	"go/ast"
	"go/parser"
	"go/token"
	"regexp"
	"slices"
	"strings"
	"testing"
)

// The walk's doc comment names every loop a walk goes over the subject, the machine or the sets
// kept in, and the files the walk is in call nothing that could loop over them out of sight: each
// function there with a loop in it is named, each name is a function with a loop, and they import
// only what goes over no more than a character. Building the machine is done once for a pattern
// and not as a subject is read, and is left out.
func TestTheWalkNamesEveryLoopItHas(t *testing.T) {
	files := []string{"pattern_machine.go", "pattern_known.go"}
	building := []string{"machine.build", "machine.repeated", "buildsNoState"}
	imports := []string{"math", "sync", "unicode/utf8"}
	fset := token.NewFileSet()
	var doc string
	var looping []string
	for _, name := range files {
		file, err := parser.ParseFile(fset, name, nil, parser.ParseComments)
		if err != nil {
			t.Fatal(err)
		}
		for _, spec := range file.Imports {
			if path := strings.Trim(spec.Path.Value, `"`); !slices.Contains(imports, path) {
				t.Errorf("%s imports %s", name, path)
			}
		}
		for _, decl := range file.Decls {
			switch decl := decl.(type) {
			case *ast.GenDecl:
				for _, spec := range decl.Specs {
					if spec, ok := spec.(*ast.TypeSpec); ok && spec.Name.Name == "walk" {
						doc = decl.Doc.Text()
					}
				}
			case *ast.FuncDecl:
				if slices.Contains(building, funcName(decl)) {
					continue
				}
				loops := false
				ast.Inspect(decl.Body, func(n ast.Node) bool {
					switch n.(type) {
					case *ast.ForStmt, *ast.RangeStmt:
						loops = true
					}
					return !loops
				})
				if loops {
					looping = append(looping, funcName(decl))
				}
			}
		}
	}
	named := map[string]bool{}
	for _, link := range regexp.MustCompile(`\[([\w.]+)\]`).FindAllStringSubmatch(doc, -1) {
		named[link[1]] = true
	}
	for _, name := range looping {
		if !named[name] {
			t.Errorf("%s loops and the walk's doc comment does not name it", name)
		}
		delete(named, name)
	}
	for name := range named {
		t.Errorf("the walk's doc comment names %s, which is no function with a loop", name)
	}
}

// funcName is a function's name as a doc link writes it: the type and the method for a method.
func funcName(decl *ast.FuncDecl) string {
	if decl.Recv == nil {
		return decl.Name.Name
	}
	recv := decl.Recv.List[0].Type
	if star, ok := recv.(*ast.StarExpr); ok {
		recv = star.X
	}
	return recv.(*ast.Ident).Name + "." + decl.Name.Name
}
