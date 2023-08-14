git config --global core.autocrlf true
git pull origin master
git add -A
git commit -m "update"
git push origin master
git tag -a v6.3.0 -m "release v6.3.0"
git push origin --tags
pause