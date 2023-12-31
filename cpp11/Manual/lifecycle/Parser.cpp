//
// Copyright (c) ZeroC, Inc. All rights reserved.
//

#include <Parser.h>

#ifdef HAVE_READLINE
#   include <readline/readline.h>
#   include <readline/history.h>
#endif

using namespace std;
using namespace Filesystem;

extern FILE* yyin;

Parser* parser;

Parser::Parser(const shared_ptr<DirectoryPrx>& root)
{
    _dirs.push_front(root);
}

void
Parser::usage()
{
    cout <<
        "help                    Print this message.\n"
        "pwd                     Print current directory (/ = root).\n"
        "cd [DIR]                Change directory (/ or empty = root).\n"
        "ls                      List current directory.\n"
        "lr                      Recursively list current directory.\n"
        "mkdir DIR [DIR...]      Create directories DIR in current directory.\n"
        "mkfile FILE [FILE...]   Create files FILE in current directory.\n"
        "rm NAME [NAME...]       Delete directory or file NAME (rm * to delete all).\n"
        "cat FILE                List the contents of FILE.\n"
        "write FILE [STRING...]  Write STRING to FILE.\n"
        "exit, quit              Exit this program.\n";
}

// Print the contents of directory "dir". If recursive is true,
// print in tree fashion.
// For files, show the contents of each file. The "depth"
// parameter is the current nesting level (for indentation).

void
Parser::list(bool recursive)
{
    list(_dirs.front(), recursive, 0);
}

void
Parser::list(const shared_ptr<DirectoryPrx>& dir, bool recursive, size_t depth)
{
    const string indent(depth++, '\t');

    auto contents = dir->list();

    for(const auto& i: contents)
    {
        shared_ptr<DirectoryPrx> d;
        if(i.type == NodeType::DirType)
        {
            d = Ice::uncheckedCast<DirectoryPrx>(i.proxy);
        }
        cout << indent << i.name << (d ? " (directory)" : " (file)");
        if(d && recursive)
        {
            cout << ":" << endl;
            list(d, true, depth);
        }
        else
        {
            cout << endl;
        }
    }
}

void
Parser::createFile(const std::list<string>& names)
{
    auto dir = _dirs.front();

    for(const auto& i: names)
    {
        if(i == "..")
        {
            cout << "Cannot create a file named `..'" << endl;
            continue;
        }

        try
        {
            dir->createFile(i);
        }
        catch(const NameInUse&)
        {
            cout << "`" << i << "' exists already" << endl;
        }
    }
}

void
Parser::createDir(const std::list<string>& names)
{
    auto dir = _dirs.front();

    for(const auto& i: names)
    {
        if(i == "..")
        {
            cout << "Cannot create a directory named `..'" << endl;
            continue;
        }

        try
        {
            dir->createDirectory(i);
        }
        catch(const NameInUse&)
        {
            cout << "`" << i << "' exists already" << endl;
        }
    }
}

void
Parser::pwd()
{
    if(_dirs.size() == 1)
    {
        cout << "/";
    }
    else
    {
        auto i = _dirs.rbegin();
        ++i;
        while(i != _dirs.rend())
        {
            cout << "/" << (*i)->name();
            ++i;
        }
    }
    cout << endl;
}

void
Parser::cd(const string& name)
{
    if(name == "/")
    {
       while(_dirs.size() > 1)
       {
           _dirs.pop_front();
       }
       return;
    }

    if(name == "..")
    {
       if(_dirs.size() > 1)
       {
           _dirs.pop_front();
       }
       return;
    }

    auto dir = _dirs.front();
    NodeDesc d;
    try
    {
        d = dir->find(name);
    }
    catch(const NoSuchName&)
    {
        cout << "`" << name << "': no such directory" << endl;
        return;
    }
    if(d.type == NodeType::FileType)
    {
        cout << "`" << name << "': not a directory" << endl;
        return;
    }
    _dirs.push_front(Ice::uncheckedCast<DirectoryPrx>(d.proxy));
}

void
Parser::cat(const string& name)
{
    auto dir = _dirs.front();
    NodeDesc d;
    try
    {
        d = dir->find(name);
    }
    catch(const NoSuchName&)
    {
        cout << "`" << name << "': no such file" << endl;
        return;
    }
    if(d.type == NodeType::DirType)
    {
        cout << "`" << name << "': not a file" << endl;
        return;
    }
    auto f = Ice::uncheckedCast<FilePrx>(d.proxy);
    auto lines = f->read();
    for(const auto& i: lines)
    {
        cout << i << endl;
    }
}

void
Parser::write(std::list<string>& args)
{
    auto dir = _dirs.front();
    auto name = args.front();
    args.pop_front();
    NodeDesc d;
    try
    {
        d = dir->find(name);
    }
    catch(const NoSuchName&)
    {
        cout << "`" << name << "': no such file" << endl;
        return;
    }
    if(d.type == NodeType::DirType)
    {
        cout << "`" << name << "': not a file" << endl;
        return;
    }
    auto f = Ice::uncheckedCast<FilePrx>(d.proxy);

    Lines l;
    for(const auto& i: args)
    {
        l.push_back(i);
    }
    f->write(l);
}

void
Parser::destroy(const std::list<string>& names)
{
    auto dir = _dirs.front();

    for(const auto& i: names)
    {
        if(i == "*")
        {
            auto nodes = dir->list();
            for(auto& j: nodes)
            {
                try
                {
                    j.proxy->destroy();
                }
                catch(const PermissionDenied& ex)
                {
                    cout << "cannot remove `" << j.name << "': " << ex.reason << endl;
                }
            }
            return;
        }
        else
        {
            NodeDesc d;
            try
            {
                d = dir->find(i);
            }
            catch(const NoSuchName&)
            {
                cout << "`" << i << "': no such file or directory" << endl;
                return;
            }
            try
            {
                d.proxy->destroy();
            }
            catch(const PermissionDenied& ex)
            {
                cout << "cannot remove `" << i << "': " << ex.reason << endl;
            }
        }
    }
}

//
// With older flex version <= 2.5.35 YY_INPUT second
// paramenter is of type int&, in newer versions it
// changes to size_t&
//
void
Parser::getInput(char* buf, int& result, size_t maxSize)
{
    auto r = static_cast<size_t>(result);
    getInput(buf, r, maxSize);
    result = static_cast<int>(r);
}

void
Parser::getInput(char* buf, size_t& result, size_t maxSize)
{
#ifdef HAVE_READLINE

    auto prompt = parser->getPrompt();
    auto line = readline(const_cast<char*>(prompt));
    if(!line)
    {
        result = 0;
    }
    else
    {
        if(*line)
        {
            add_history(line);
        }

        result = strlen(line) + 1;
        if(result > maxSize)
        {
            free(line);
            error("input line too long");
            result = 0;
        }
        else
        {
            strcpy(buf, line);
            strcat(buf, "\n");
            free(line);
        }
    }

#else

    cout << parser->getPrompt() << flush;

    string line;
    int c;
    do
    {
        c = getc(yyin);
        if(c == EOF)
        {
            if(line.size())
            {
                line += '\n';
            }
        }
        else
        {
            line += static_cast<char>(c);
        }
    } while(c != EOF && c != '\n');

    result = line.length();
    if(result > maxSize)
    {
        error("input line too long");
        buf[0] = EOF;
        result = 1;
    }
    else
    {
#   ifdef _WIN32
        strcpy_s(buf, result + 1, line.c_str());
#   else
        strcpy(buf, line.c_str());
#   endif
    }

#endif
}

void
Parser::continueLine()
{
    _continue = true;
}

const char*
Parser::getPrompt()
{
    if(_continue)
    {
        _continue = false;
        return "(cont) ";
    }
    else
    {
        return "> ";
    }
}

void
Parser::error(const char* s)
{
    cerr << "error: " << s << endl;
    _errors++;
}

void
Parser::error(const string& s)
{
    error(s.c_str());
}

void
Parser::warning(const char* s)
{
    cerr << "warning: " << s << endl;
}

void
Parser::warning(const string& s)
{
    warning(s.c_str());
}

int
Parser::parse(bool debug)
{
    extern int yydebug;
    yydebug = debug ? 1 : 0;

    assert(!parser);
    parser = this;

    _errors = 0;
    yyin = stdin;
    assert(yyin);

    _continue = false;

    usage();

    int status = yyparse();
    if(_errors)
    {
        status = 1;
    }

    parser = 0;
    return status;
}
